package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static gd.script.gdcc.rpc.RpcHttpTestClient.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the shipped jar's bundle discovery, extraction and automatic Debug backend,
/// without an environment override or a previously installed user bundle.
class RpcPackagedTinyCcIntegrationTest {
    @Test
    void packagedServerDiscoversAndBuildsTinyCcInAutomaticMode(@TempDir Path tempDir) throws Exception {
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(Set.of(TargetPlatform.WINDOWS_X86_64, TargetPlatform.LINUX_X86_64,
                TargetPlatform.LINUX_AARCH64, TargetPlatform.MACOS_AARCH64).contains(host));
        var jar = System.getProperty("gdcc.test.jar");
        Assumptions.assumeTrue(jar != null, "Run via Gradle to build the production jar");
        var windows = host == TargetPlatform.WINDOWS_X86_64;
        var home = Files.createDirectories(tempDir.resolve("home"));
        var nativeResources = Files.createDirectories(tempDir.resolve("native"));
        var output = tempDir.resolve("server.log");
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var javaBin = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        var builder = new ProcessBuilder(List.of(javaBin.toString(), "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + home, "-Dgdparser.gdscript.resourceDir=" + nativeResources,
                "-jar", jar, "serve", "--host", "127.0.0.1", "--port", Integer.toString(port)))
                .redirectErrorStream(true).redirectOutput(output.toFile());
        var environment = builder.environment();
        environment.remove("GDCC_TINYCC_HOME");
        environment.remove("GDCC_TINYCC_TCC");
        environment.put("LOCALAPPDATA", home.toString());
        environment.put("GDCC_SHARED_C_COMPILER_CACHE", tempDir.resolve("compiler-cache").toString());
        environment.put("GDCC_SHARED_INCLUDE", tempDir.resolve("include-cache").toString());
        var process = builder.start();
        try {
            var rpc = new RpcHttpTestClient(URI.create("http://127.0.0.1:" + port + "/rpc"));
            var ready = false;
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    ready = rpc.callForResult("server.ping", null).getAsString().equals("pong");
                    break;
                } catch (IOException _) {
                    Thread.sleep(100);
                }
            }
            assertTrue(ready, "Packaged server did not start: " + Files.readString(output));
            var kinds = rpc.callForResult("server.availableCCompilerKinds", null).getAsJsonArray();
            assertTrue(kinds.asList().stream().anyMatch(kind -> kind.getAsString().equals("TINY_CC")),
                    "The shipped jar must expose TinyCC without GDCC_TINYCC_HOME: " + kinds);

            rpc.callForResult("module.create", params("moduleId", "packaged", "moduleName", "Packaged TinyCC"));
            rpc.callForResult("vfs.putFile", params("moduleId", "packaged", "path", "/src/main.gd",
                    "content", """
                            class_name PackagedTinyCcProbe
                            extends RefCounted
                            func value() -> int:
                                return 1
                            """));
            var options = rpc.callForResult("options.get", params("moduleId", "packaged")).getAsJsonObject();
            assertTrue(options.get("cCompilerKind").isJsonNull());
            assertEquals("DEBUG", options.get("optimizationLevel").getAsString());
            options.addProperty("projectPath", tempDir.resolve("project").toString());
            rpc.callForResult("options.set", params("moduleId", "packaged", "compileOptions", options));
            var taskId = rpc.callForResult("compile.start", params("moduleId", "packaged"))
                    .getAsJsonObject().get("taskId").getAsLong();
            var task = new JsonObject();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                task = rpc.callForResult("compile.getTask", params("taskId", taskId)).getAsJsonObject();
                if (Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(task.get("state").getAsString())) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals("SUCCEEDED", task.get("state").getAsString(), "Automatic Debug build failed: " + task);
            var result = task.getAsJsonObject("result");
            assertEquals("SUCCESS", result.get("outcome").getAsString());
            assertTrue(result.getAsJsonObject("compileOptions").get("cCompilerKind").isJsonNull());
            assertTrue(result.get("buildLog").getAsString().contains("tcc object cache"), result.toString());
            assertTrue(Files.isRegularFile(Path.of(result.getAsJsonArray("artifacts").get(0).getAsString())));
            var cache = windows ? home.resolve("gdcc/tinycc") : home.resolve(".cache/gdcc/tinycc");
            try (var files = Files.walk(cache)) {
                assertTrue(files.anyMatch(file -> file.getFileName().toString().equals(".ready")),
                        "The shipped bundle must be extracted and verified into the empty cache");
            }
            rpc.callForResult("server.shutdown", null);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Packaged server did not shut down");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

}
