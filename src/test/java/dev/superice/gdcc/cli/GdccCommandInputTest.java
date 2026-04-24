package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import dev.superice.gdcc.api.ModuleSnapshot;
import dev.superice.gdcc.api.VfsEntrySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccCommandInputTest {
    @Test
    void loadsInputFilesIntoOneModuleWithStableVirtualPathsAndDisplayPaths(@TempDir Path tempDir) throws IOException {
        var firstSource = writeSource(tempDir.resolve("one/main.gd"), "extends Node\nvar label = \"玩家\"\n");
        var secondSource = writeSource(tempDir.resolve("two/main.gd"), "extends RefCounted\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                firstSource.toString(),
                secondSource.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("compile task execution is not implemented yet"), terminal.errText());
        assertEquals(List.of("demo"), terminal.api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
        assertEquals(1, terminal.api.getModule("demo").rootEntryCount());
        assertEquals(List.of("0000", "0001"), terminal.api.listDirectory("demo", "/src").stream()
                .map(VfsEntrySnapshot::name)
                .toList());

        assertSourceFile(terminal.api, "/src/0000/main.gd", firstSource.toString(), "extends Node\nvar label = \"玩家\"\n");
        assertSourceFile(terminal.api, "/src/0001/main.gd", secondSource.toString(), "extends RefCounted\n");
    }

    @Test
    void duplicateHostFileArgumentsStayDistinctInVirtualFileSystem(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), "extends Node\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                source.toString(),
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertSourceFile(terminal.api, "/src/0000/player.gd", source.toString(), "extends Node\n");
        assertSourceFile(terminal.api, "/src/0001/player.gd", source.toString(), "extends Node\n");
    }

    @Test
    void missingInputFileFailsBeforeCreatingModule(@TempDir Path tempDir) throws IOException {
        var existingSource = writeSource(tempDir.resolve("player.gd"), "extends Node\n");
        var missingSource = tempDir.resolve("missing.gd");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                existingSource.toString(),
                missingSource.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Input file does not exist"), terminal.errText());
        terminal.assertNoStepBoundary();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void directoryInputFailsBeforeCreatingModule(@TempDir Path tempDir) throws IOException {
        var directory = tempDir.resolve("source-dir");
        Files.createDirectories(directory);
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                directory.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Input path is a directory"), terminal.errText());
        terminal.assertNoStepBoundary();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void invalidOutputPathFailsBeforeCreatingModule(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), "extends Node\n");
        var blankOutput = new Terminal();
        var rootOutput = new Terminal();

        var blankExitCode = blankOutput.command().commandLine().execute("-o", "", source.toString());
        var rootExitCode = rootOutput.command().commandLine().execute("-o", tempDir.getRoot().toString(), source.toString());

        assertEquals(GdccCommand.EXIT_USAGE, blankExitCode);
        assertTrue(blankOutput.errText().contains("Output path must not be blank"), blankOutput.errText());
        blankOutput.assertNoStepBoundary();
        assertTrue(blankOutput.api.listModules().isEmpty());

        assertEquals(GdccCommand.EXIT_USAGE, rootExitCode);
        assertTrue(rootOutput.errText().contains("Output path must include a module name"), rootOutput.errText());
        rootOutput.assertNoStepBoundary();
        assertTrue(rootOutput.api.listModules().isEmpty());
    }

    @Test
    void apiModuleCreationFailureIsRenderedAsUsageError(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), "extends Node\n");
        var terminal = new Terminal();
        terminal.api.createModule("demo", "Existing Demo");

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Module 'demo' already exists"), terminal.errText());
        terminal.assertNoStepBoundary();
        assertEquals(0, terminal.api.getModule("demo").rootEntryCount());
    }

    private static void assertSourceFile(API api, String virtualPath, String displayPath, String source) {
        var entry = assertInstanceOf(VfsEntrySnapshot.FileEntrySnapshot.class, api.readEntry("demo", virtualPath));
        assertEquals(virtualPath, entry.virtualPath());
        assertEquals(displayPath, entry.path());
        assertEquals(source, api.readFile("demo", virtualPath));
    }

    private static Path writeSource(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private static final class Terminal {
        private final API api = new API();
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();

        GdccCommand command() {
            return new GdccCommand(api, new PrintWriter(out, true), new PrintWriter(err, true));
        }

        String errText() {
            return err.toString();
        }

        void assertNoStepBoundary() {
            assertFalse(errText().contains("compile task execution is not implemented yet"), errText());
        }
    }
}
