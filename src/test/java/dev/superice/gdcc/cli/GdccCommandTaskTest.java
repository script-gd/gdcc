package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.CompileResult;
import dev.superice.gdcc.api.CompileTaskEvent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(CompileResult.Outcome.SUCCESS, terminal.api.getLastCompileResult("demo").outcome());
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
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void buildFailureReturnsOneAndPrintsBuildLog(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.failing("zig cc failed"));

        var exitCode = terminal.command().commandLine().execute("-o", tempDir.resolve("build/demo").toString(), source.toString());

        assertEquals(GdccCommand.EXIT_COMPILE_FAILED, exitCode);
        assertTrue(terminal.errText().contains("Compile failed: BUILD_FAILED"), terminal.errText());
        assertTrue(terminal.errText().contains("zig cc failed"), terminal.errText());
    }

    @Test
    void verboseCompileDrainsRetainedEventsWithIndexedPagination(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal(CliCompileTestSupport.TestCompiler.succeedingWithEvents(
                new CompileTaskEvent("backend.codegen", "prepared C sources"),
                new CompileTaskEvent("backend.build", "called native compiler")
        ));

        var exitCode = terminal.command().commandLine().execute(
                "-v",
                "-o", tempDir.resolve("build/demo").toString(),
                source.toString()
        );

        assertEquals(0, exitCode);
        assertTrue(terminal.errText().contains("Task 1"), terminal.errText());
        assertTrue(terminal.errText().contains("Event 0 backend.codegen: prepared C sources"), terminal.errText());
        assertTrue(terminal.errText().contains("Event 1 backend.build: called native compiler"), terminal.errText());
    }

    @Test
    void interruptedWaitCancelsCompileTaskAndReturnsInterruptExitCode(@TempDir Path tempDir) throws Exception {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var compiler = CliCompileTestSupport.TestCompiler.blockingSuccess();
        var terminal = new Terminal(compiler);
        var exitCode = new AtomicReference<Integer>();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> exitCode.set(terminal.command().commandLine().execute(
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

    private static final class Terminal {
        private final dev.superice.gdcc.api.API api;
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
