package gd.script.gdcc.launcher;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// End-to-end smoke for the native launcher's backend contract (`src/launcher/zig`), driven through
/// the real launcher binary plus the real distribution jar:
/// - zig discovery is optional: a launcher that cannot find zig anywhere still boots the JVM and
///   leaves `ZIG_HOME` unset (previously it exited with an error); when zig IS reachable the
///   launcher keeps propagating `ZIG_HOME` as before;
/// - `--backend tcc` compiles end to end through launcher → JVM → in-process libtcc without any
///   zig in the environment, while `--backend zig` under the same conditions surfaces the
///   existing "Zig executable not found" channel instead of a launcher-level failure.
///
/// Prerequisites (mirroring the tinycc-CLI test convention, tests never spawn a build): the
/// `buildZigLauncher` and `jar` Gradle outputs must exist. "zig unreachable" is produced by
/// scrubbing the process environment and, when a zig still sits in the launcher's hardcoded common
/// paths, bind-mounting `/dev/null` over those files inside a user+mount namespace (`unshare`).
class LauncherSmokeTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path LAUNCHER_BINARY = PROJECT_ROOT.resolve("build/launcher/zig/bin/linux-x86_64/gdcc");
    private static final Path DIST_LIBS_DIR = PROJECT_ROOT.resolve("build/libs");
    private static final Path PARSER_NATIVE_DIR = PROJECT_ROOT.resolve("native");
    private static final Path TINYCC_BUNDLE_DIR = PROJECT_ROOT.resolve("build/tinycc-bundle/linux-x86_64");
    /// The launcher's hardcoded fallback locations for Linux (see `zigCommonPaths`).
    private static final List<Path> LAUNCHER_COMMON_ZIG_PATHS = List.of(
            Path.of("/usr/bin/zig"),
            Path.of("/usr/local/bin/zig"),
            Path.of("/snap/bin/zig")
    );
    private static final long PROCESS_TIMEOUT_MINUTES = 5;

    @org.junit.jupiter.api.BeforeEach
    void requireLinuxX86_64Host() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            Assumptions.abort("the launcher smoke requires a linux-x86_64 host");
        }
    }

    @Test
    void tccBackendCompilesThroughLauncherWithoutZig(@TempDir Path tempDir) throws Exception {
        if (!isUsableBundle(TINYCC_BUNDLE_DIR)) {
            Assumptions.abort("tinycc bundle not built; run build-script/build-tinycc-bundle-linux-x86_64.sh first");
            return;
        }
        var stage = stageFullDistribution(tempDir);
        var environment = scrubbedEnvironment(tempDir);
        environment.put("GDCC_TINYCC_HOME", TINYCC_BUNDLE_DIR.toString());
        var outputTarget = tempDir.resolve("out/demo");
        var source = writeFixtureSource(tempDir);

        // Mask every discoverable zig as well: without masking, a mis-dispatch to the zig backend
        // could still succeed on machines with zig installed and the test would not notice.
        var command = maskingZigExecutables(
                List.of(stage.resolve("gdcc").toString(), "--backend", "tcc",
                        "-o", outputTarget.toString(), source.toString()),
                zigFilesReachableByDiscovery()
        );
        var result = run(command, environment);

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(result.stdout().contains("Compiled module demo"), () -> result.describe());
        try (var walk = Files.walk(outputTarget)) {
            var artifacts = walk.filter(path -> path.getFileName().toString().endsWith(".so")).toList();
            assertFalse(artifacts.isEmpty(), () -> "expected a shared library under " + outputTarget + "\n" + result.describe());
        }
    }

    @Test
    void launcherPropagatesZigHomeWhenZigIsDiscoverable(@TempDir Path tempDir) throws Exception {
        if (LAUNCHER_COMMON_ZIG_PATHS.stream().noneMatch(Files::isExecutable)) {
            Assumptions.abort("no zig in the launcher's common paths; nothing to propagate");
            return;
        }
        var stage = stageProbeDistribution(tempDir);

        var result = run(List.of(stage.resolve("gdcc").toString()), scrubbedEnvironment(tempDir));

        assertEquals(0, result.exitCode(), () -> result.describe());
        var zigHome = probeOutputZigHome(result.stdout());
        assertTrue(zigHome != null && !zigHome.equals("<unset>"), () -> "ZIG_HOME must be set: " + result.describe());
        assertTrue(Files.isExecutable(Path.of(zigHome).resolve("zig")), () -> "ZIG_HOME must point at a zig directory: " + zigHome);
    }

    @Test
    void launcherContinuesWithoutZigHomeWhenZigIsUnreachable(@TempDir Path tempDir) throws Exception {
        var stage = stageProbeDistribution(tempDir);
        var command = maskingZigExecutables(List.of(stage.resolve("gdcc").toString()), zigFilesReachableByDiscovery());

        var result = run(command, scrubbedEnvironment(tempDir));

        // The pre-change behavior exited 1 with "unable to find Zig" before even starting the JVM.
        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals("<unset>", probeOutputZigHome(result.stdout()), () -> result.describe());
    }

    @Test
    void zigBackendWithoutZigReportsTheExistingMissingZigChannel(@TempDir Path tempDir) throws Exception {
        var stage = stageFullDistribution(tempDir);
        var source = writeFixtureSource(tempDir);
        var command = maskingZigExecutables(
                List.of(stage.resolve("gdcc").toString(), "--backend", "zig",
                        "-o", tempDir.resolve("out/demo").toString(), source.toString()),
                zigFilesReachableByDiscovery()
        );

        var result = run(command, scrubbedEnvironment(tempDir));

        // The launcher boots fine; the zig backend itself reports the gap at compile time.
        assertEquals(1, result.exitCode(), () -> result.describe());
        assertTrue(
                result.stderr().contains("Zig executable not found on PATH or known locations"),
                () -> result.describe()
        );
    }

    /// The zig executable files reachable by EITHER side's discovery (launcher common paths plus
    /// the JVM-side home-relative ones): each must be masked for a "zig unreachable" run.
    private static List<Path> zigFilesReachableByDiscovery() {
        var candidates = new ArrayList<>(LAUNCHER_COMMON_ZIG_PATHS);
        var home = System.getProperty("user.home");
        if (home != null) {
            candidates.add(Path.of(home, ".zigup", "bin", "zig"));
            candidates.add(Path.of(home, ".local", "bin", "zig"));
        }
        return candidates.stream().filter(Files::isExecutable).toList();
    }

    /// Wraps the command in a user+mount namespace binding `/dev/null` over every reachable zig
    /// executable, so both the launcher and the JVM-side discovery see "no zig" on machines that
    /// actually have one. The file-level bind keeps the rest of the host filesystem untouched.
    /// Returns the command unchanged when there is nothing to mask; aborts when masking is needed
    /// but user namespaces are unavailable.
    private static List<String> maskingZigExecutables(List<String> command, List<Path> zigFiles) {
        if (zigFiles.isEmpty()) {
            return command;
        }
        if (!userNamespaceAvailable()) {
            Assumptions.abort("zig is installed and user namespaces are unavailable for masking it");
            return command; // unreachable, abort throws
        }
        // The scrubbed environment has an empty PATH, so the in-namespace mount must be
        // referenced by its absolute path.
        var mount = List.of("/usr/bin/mount", "/bin/mount").stream()
                .map(Path::of)
                .filter(Files::isExecutable)
                .findFirst();
        if (mount.isEmpty()) {
            Assumptions.abort("no mount binary found for the namespace zig masking");
            return command; // unreachable, abort throws
        }
        var script = new StringBuilder();
        for (var zigFile : zigFiles) {
            script.append(mount.get()).append(" --bind /dev/null '").append(zigFile).append("' && ");
        }
        script.append("exec \"$0\" \"$@\"");
        var wrapped = new ArrayList<String>();
        wrapped.add("unshare");
        wrapped.add("--user");
        wrapped.add("--map-root-user");
        wrapped.add("--mount");
        wrapped.add("--");
        wrapped.add("/bin/sh");
        wrapped.add("-c");
        wrapped.add(script.toString());
        wrapped.addAll(command);
        return wrapped;
    }

    private static boolean userNamespaceAvailable() {
        try {
            return new ProcessBuilder("unshare", "--user", "--map-root-user", "true")
                    .start()
                    .waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }

    /// Stages a minimal but complete distribution: launcher binary + main jar + its `lib/`
    /// dependencies + the parser's `native/` resources, mirroring the packaged layout the
    /// launcher expects next to itself.
    private static Path stageFullDistribution(Path tempDir) throws IOException {
        var stage = Files.createDirectories(tempDir.resolve("stage"));
        copyExecutable(LAUNCHER_BINARY, stage.resolve("gdcc"));
        var jar = requireDistJar();
        Files.copy(jar, stage.resolve(jar.getFileName().toString()));
        var libDir = DIST_LIBS_DIR.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            Assumptions.abort("distribution dependency directory is missing: " + libDir);
            return stage; // unreachable, abort throws
        }
        copyRecursively(libDir, stage.resolve("lib"));
        copyRecursively(PARSER_NATIVE_DIR, stage.resolve("native"));
        return stage;
    }

    /// Stages the launcher with a probe jar that just prints its `ZIG_HOME` environment variable:
    /// the probe observes the launcher's child-environment contract without the full compiler.
    private static Path stageProbeDistribution(Path tempDir) throws IOException {
        var stage = Files.createDirectories(tempDir.resolve("stage"));
        copyExecutable(LAUNCHER_BINARY, stage.resolve("gdcc"));
        writeProbeJar(stage.resolve("gdcc-0.0.0.jar"), tempDir);
        return stage;
    }

    /// Compiles and packages the probe in-test so no checked-in binary fixture is needed. The
    /// jar name must satisfy the launcher's `gdcc-<three-segment-version>.jar` rule.
    private static void writeProbeJar(Path jarFile, Path tempDir) throws IOException {
        var javaCompiler = ToolProvider.getSystemJavaCompiler();
        if (javaCompiler == null) {
            Assumptions.abort("a JDK (not JRE) is required to compile the probe jar");
            return;
        }
        var probeBuild = Files.createDirectories(tempDir.resolve("probe-build"));
        var sourceFile = probeBuild.resolve("Main.java");
        Files.writeString(sourceFile, """
                public final class Main {
                    public static void main(String[] args) {
                        var zigHome = System.getenv("ZIG_HOME");
                        System.out.println("ZIG_HOME=" + (zigHome == null ? "<unset>" : zigHome));
                    }
                }
                """);
        var classes = Files.createDirectories(probeBuild.resolve("classes"));
        if (javaCompiler.run(null, null, null, "-d", classes.toString(), sourceFile.toString()) != 0) {
            throw new IOException("probe jar compilation failed");
        }
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        try (var jar = new JarOutputStream(Files.newOutputStream(jarFile), manifest)) {
            jar.putNextEntry(new JarEntry("Main.class"));
            Files.copy(classes.resolve("Main.class"), jar);
            jar.closeEntry();
        }
    }

    /// A minimal environment in which zig is undiscoverable through every environment-driven
    /// channel: no ZIG* variables, a PATH containing no tools at all, and an empty HOME so the
    /// JVM-side discovery finds no home-relative zig either. `JAVA_HOME` keeps the launcher's
    /// Java discovery working.
    private static Map<String, String> scrubbedEnvironment(Path tempDir) throws IOException {
        var environment = new HashMap<String, String>();
        environment.put("PATH", Files.createDirectories(tempDir.resolve("empty-path")).toString());
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("HOME", Files.createDirectories(tempDir.resolve("empty-home")).toString());
        return environment;
    }

    private static ExecResult run(List<String> command, Map<String, String> environment) throws IOException, InterruptedException {
        var stdoutFile = Files.createTempFile("launcher-smoke-", ".out");
        var stderrFile = Files.createTempFile("launcher-smoke-", ".err");
        try {
            var processBuilder = new ProcessBuilder(command);
            processBuilder.environment().clear();
            processBuilder.environment().putAll(environment);
            processBuilder.redirectOutput(stdoutFile.toFile());
            processBuilder.redirectError(stderrFile.toFile());
            var process = processBuilder.start();
            if (!process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("launcher invocation timed out: " + String.join(" ", command));
            }
            return new ExecResult(
                    process.exitValue(),
                    Files.readString(stdoutFile, StandardCharsets.UTF_8),
                    Files.readString(stderrFile, StandardCharsets.UTF_8),
                    String.join(" ", command)
            );
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    private static String probeOutputZigHome(String stdout) {
        for (var line : stdout.split("\n")) {
            if (line.startsWith("ZIG_HOME=")) {
                return line.substring("ZIG_HOME=".length()).trim();
            }
        }
        return null;
    }

    private static boolean isUsableBundle(Path root) {
        return Files.isRegularFile(root.resolve("VERSION"))
                && Files.isRegularFile(root.resolve("MANIFEST"))
                && Files.isRegularFile(root.resolve("bin/libtcc.so"))
                && Files.isRegularFile(root.resolve("libtcc1.a"));
    }

    private static Path requireDistJar() {
        if (Files.isDirectory(DIST_LIBS_DIR)) {
            try (var entries = Files.list(DIST_LIBS_DIR)) {
                var jar = entries.filter(entry -> {
                    var name = entry.getFileName().toString();
                    return name.startsWith("gdcc-") && name.endsWith(".jar") && !name.endsWith("-update.jar");
                }).findFirst();
                if (jar.isPresent()) {
                    return jar.get();
                }
            } catch (IOException exception) {
                // Fall through to the abort below.
            }
        }
        Assumptions.abort("distribution jar not built; run the Gradle `jar` task first");
        throw new IllegalStateException("unreachable");
    }

    private static Path writeFixtureSource(Path tempDir) throws IOException {
        var source = tempDir.resolve("fixture/player.gd");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class_name Player
                extends RefCounted

                func value() -> int:
                    return 3
                """, StandardCharsets.UTF_8);
        return source;
    }

    private static void copyExecutable(Path source, Path target) throws IOException {
        if (!Files.isExecutable(source)) {
            Assumptions.abort("launcher binary missing; run the Gradle `buildZigLauncher` task first: " + source);
            return;
        }
        Files.copy(source, target);
        if (!target.toFile().setExecutable(true)) {
            throw new IOException("could not mark the staged launcher executable: " + target);
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (var entry : walk.toList()) {
                var destination = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination);
                }
            }
        }
    }

    private record ExecResult(int exitCode, String stdout, String stderr, String command) {
        private String describe() {
            return "command: " + command + "\nexit: " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }
}
