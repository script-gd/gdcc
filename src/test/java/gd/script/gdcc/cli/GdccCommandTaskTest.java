package gd.script.gdcc.cli;

import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.api.CompileTaskEvent;
import gd.script.gdcc.api.API;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccCommandTaskTest {
    @Test
    void successfulCompileReturnsZeroAndPrintsArtifacts(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.succeeding());

        var exitCode = terminal.command().commandLine().execute("-o", tempDir.resolve("build/demo").toString(), source.toString());

        assertEquals(0, exitCode);
        assertTrue(terminal.outText().contains("Compiled module demo"), terminal.outText());
        assertTrue(terminal.outText().contains("Artifact:"), terminal.outText());
        assertEquals(CompileResult.Outcome.SUCCESS,
                CliCompileTestSupport.awaitLastResult(terminal.api, "demo").outcome());
        assertFalse(terminal.outText().contains("Output link:"), terminal.outText());
        assertFalse(terminal.outText().contains("Compile option:"), terminal.outText());
        assertFalse(terminal.outText().contains("Build log:"), terminal.outText());
    }

    @Test
    void frontendFailureReturnsOneAndRendersDisplayPathDiagnostic(@TempDir Path tempDir) throws IOException {
        var first = writeSource(tempDir.resolve("one/shared.gd"), validSource("SharedName"));
        var second = writeSource(tempDir.resolve("two/shared.gd"), validSource("SharedName"));
        var compiler = CliCompileTestSupport.TestCompiler.succeeding();
        var terminal = new Terminal(compiler);

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                first.toString(),
                second.toString()
        );

        assertEquals(GdccCommand.EXIT_COMPILE_FAILED, exitCode);
        assertTrue(terminal.errText().contains("Compile failed: FRONTEND_FAILED"), terminal.errText());
        assertTrue(
                terminal.errText().contains(normalizedPath(first)) || terminal.errText().contains(normalizedPath(second)),
                terminal.errText()
        );
        assertTrue(terminal.errText().contains("ERROR sema.class_skeleton"), terminal.errText());
        assertTrue(terminal.errText().contains(":1:1:"), terminal.errText());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void buildFailureReturnsOneAndPrintsBuildLog(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.failing("zig cc failed"));

        var exitCode = terminal.command().commandLine().execute("-o", tempDir.resolve("build/demo").toString(), source.toString());

        assertEquals(GdccCommand.EXIT_COMPILE_FAILED, exitCode);
        assertTrue(terminal.errText().contains("Compile failed: BUILD_FAILED"), terminal.errText());
        assertTrue(terminal.errText().contains("Build log:"), terminal.errText());
        assertTrue(terminal.errText().contains("zig cc failed"), terminal.errText());
    }

    @Test
    void verboseCompileDrainsRetainedEventsWithIndexedPagination(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var events = IntStream.rangeClosed(0, API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE)
                .mapToObj(index -> new CompileTaskEvent("backend.event", "event-" + index))
                .toArray(CompileTaskEvent[]::new);
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.succeedingWithEvents(events));

        var exitCode = terminal.command().commandLine().execute(
                "-v",
                "-o", tempDir.resolve("build/demo").toString(),
                source.toString()
        );

        assertEquals(0, exitCode);
        assertTrue(terminal.errText().contains("Task 1"), terminal.errText());
        assertTrue(terminal.errText().contains("Event 0 backend.event: event-0"), terminal.errText());
        assertTrue(terminal.errText().contains("Event 1000 backend.event: event-1000"), terminal.errText());
        assertTrue(terminal.outText().contains("Output link: /__build__/artifacts/"), terminal.outText());
        assertFalse(terminal.outText().contains("Compile option:"), terminal.outText());
        assertFalse(terminal.outText().contains("Build log:"), terminal.outText());
    }

    @Test
    void doubleVerboseCompilePrintsInputsGeneratedFilesCanonicalMapAndSuccessfulBuildLog(@TempDir Path tempDir)
            throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.succeeding("native compiler warnings"));

        var exitCode = terminal.command().commandLine().execute(
                "-vv",
                "--prefix", "Game_",
                "--opt", "release",
                "--target", "web-wasm32",
                "-o", tempDir.resolve("build/demo").toString(),
                source.toString()
        );

        assertEquals(0, exitCode);
        assertTrue(terminal.outText().contains("Compile option: godotVersion=4.5.1"), terminal.outText());
        assertTrue(terminal.outText().contains("Compile option: optimizationLevel=RELEASE"), terminal.outText());
        assertTrue(terminal.outText().contains("Compile option: targetPlatform=WEB_WASM32"), terminal.outText());
        assertTrue(terminal.outText().contains("Compile option: outputMountRoot=/__build__"), terminal.outText());
        assertTrue(terminal.outText().contains("Source: " + source), terminal.outText());
        assertTrue(terminal.outText().contains("Top-level canonical map: Player=Game_Player"), terminal.outText());
        assertTrue(terminal.outText().contains("Generated file:"), terminal.outText());
        assertTrue(terminal.outText().contains("Output link: /__build__/generated/"), terminal.outText());
        assertTrue(terminal.outText().contains("Build log:"), terminal.outText());
        assertTrue(terminal.outText().contains("native compiler warnings"), terminal.outText());
    }

    @Test
    void interruptedWaitCancelsCompileTaskAndReturnsInterruptExitCode(@TempDir Path tempDir) throws Exception {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var compiler = CliCompileTestSupport.TestCompiler.blockingSuccess();
        var terminal = new Terminal(compiler);
        var exitCode = new AtomicReference<Integer>();

        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> exitCode.set(terminal.command().commandLine().execute(
                    "-o", tempDir.resolve("build/demo").toString(),
                    source.toString()
            )));
            assertTrue(compiler.awaitEntered());

            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(GdccCommand.EXIT_INTERRUPTED, exitCode.get());
            assertEquals(CompileResult.Outcome.CANCELED,
                    CliCompileTestSupport.awaitLastResult(terminal.api, "demo").outcome());
        } finally {
            compiler.release();
        }
    }

    private static Path writeSource(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private static String normalizedPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 3
                """.formatted(className);
    }

    private static String validDefaultSource() {
        return """
                extends RefCounted
                
                func value() -> int:
                    return 3
                """;
    }

    private static final class Terminal {
        private final API api;
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();

        Terminal(CliCompileTestSupport.TestCompiler compiler) {
            api = CliCompileTestSupport.newApi(compiler);
        }

        GdccCommand command() {
            return new GdccCommand(api, new PrintWriter(out, true), new PrintWriter(err, true));
        }

        String outText() {
            return out.toString();
        }

        String errText() {
            return err.toString();
        }
    }
}
