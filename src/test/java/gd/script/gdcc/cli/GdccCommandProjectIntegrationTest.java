package gd.script.gdcc.cli;

import gd.script.gdcc.api.ModuleSnapshot;
import gd.script.gdcc.api.VfsEntrySnapshot;
import gd.script.gdcc.backend.c.build.CCompilerKind;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.cli.CliCompileTestSupport.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Project-mode end-to-end coverage: `gdcc <project.godot> [x.gd ...]` collects auto-discovered
/// `.gd3` plus explicit `.gd` into one module compile, with project settings merged under
/// explicit CLI options.
class GdccCommandProjectIntegrationTest {
    @Test
    void projectEntryCompilesDiscoveredGd3IntoDefaultWorkDirectory(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "AlphaGame", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("Alpha"));
        writeFile(tempDir.resolve("game/nested/b.gd3"), validSource("Beta"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(0, exitCode);
        var moduleId = singleModuleId(terminal);
        var workDir = tempDir.resolve("game/.godot/gdcc/cli").resolve(moduleId).toRealPath();
        assertEquals(workDir, terminal.api.getCompileOptions(moduleId).projectPath());

        assertEntry(terminal, moduleId, "/src/a.gd3", "res://a.gd3");
        assertEntry(terminal, moduleId, "/src/nested/b.gd3", "res://nested/b.gd3");
        var result = CliCompileTestSupport.awaitLastResult(terminal.api, moduleId);
        assertEquals(List.of("res://a.gd3", "res://nested/b.gd3"), result.sourcePaths());
    }

    @Test
    void explicitGdJoinsGd3InOneModuleAndCrossReferencesCompile(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/shared.gd3"), """
                class_name SharedBox
                extends RefCounted

                func value() -> int:
                    return 3
                """);
        var helper = writeFile(tempDir.resolve("game/helper.gd"), """
                extends SharedBox

                func double() -> int:
                    return value() * 2
                """);
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString(),
                helper.toString()
        );

        assertEquals(0, exitCode, terminal.errText());
        assertEntry(terminal, "demo", "/src/shared.gd3", "res://shared.gd3");
        assertEntry(terminal, "demo", "/src/helper.gd", "res://helper.gd");
        var result = CliCompileTestSupport.awaitLastResult(terminal.api, "demo");
        assertEquals(List.of("res://helper.gd", "res://shared.gd3"), result.sourcePaths());
    }

    @Test
    void unlistedGdWithSyntaxErrorsNeverEntersTheModule(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/ok.gd3"), validSource("Ok"));
        writeFile(tempDir.resolve("game/broken.gd"), "this is not gdscript (((\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString()
        );

        // The unspecified .gd would fail the frontend had it been collected; success proves it
        // stayed outside both the VFS and the compile task.
        assertEquals(0, exitCode, terminal.errText());
        assertEntry(terminal, "demo", "/src/ok.gd3", "res://ok.gd3");
        assertEquals(List.of("ok.gd3"), terminal.api.listDirectory("demo", "/src").stream()
                .map(VfsEntrySnapshot::name).toList());
    }

    @Test
    void projectSettingsApplyAndExplicitCliOptionsOverride(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", """
                [gdcc]
                build/optimization_level="RELEASE"
                build/strict_mode=true
                build/c_compiler_kind="ZIG_CC"
                """);
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var inherited = new Terminal();
        var overridden = new Terminal();

        var inheritedExit = inherited.command().commandLine().execute(
                "-o", tempDir.resolve("build/inherit").toString(),
                projectFile.toString());
        var overriddenExit = overridden.command().commandLine().execute(
                "--opt", "debug", "--backend", "tcc",
                "-o", tempDir.resolve("build/explicit").toString(),
                projectFile.toString());

        assertEquals(0, inheritedExit);
        var inheritedOptions = inherited.api.getCompileOptions("inherit");
        assertEquals(COptimizationLevel.RELEASE, inheritedOptions.optimizationLevel());
        assertEquals(true, inheritedOptions.strictMode());
        assertEquals(CCompilerKind.ZIG_CC, inheritedOptions.cCompilerKind());

        assertEquals(0, overriddenExit);
        var overriddenOptions = overridden.api.getCompileOptions("explicit");
        assertEquals(COptimizationLevel.DEBUG, overriddenOptions.optimizationLevel());
        assertEquals(true, overriddenOptions.strictMode());
        assertEquals(CCompilerKind.TINY_CC, overriddenOptions.cCompilerKind());
    }

    @Test
    void explicitGdOnlyWithoutAnyGd3IsALegalSourceSet(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var helper = writeFile(tempDir.resolve("game/helper.gd"), validSource("Helper"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString(), helper.toString()
        );

        assertEquals(0, exitCode, terminal.errText());
        assertEntry(terminal, "demo", "/src/helper.gd", "res://helper.gd");
    }

    @Test
    void multipleProjectPositionalsFailBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var first = writeProject(tempDir.resolve("one"), "One", "");
        var second = writeProject(tempDir.resolve("two"), "Two", "");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(first.toString(), second.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Only one project.godot"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void nonGdCompanionArgumentFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var gd3Arg = writeFile(tempDir.resolve("game/b.gd3"), validSource("B"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                projectFile.toString(), gd3Arg.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("only .gd"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void badProjectConfigFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", """
                [gdcc]
                build/optimization_level="TURBO"
                """);
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("optimization_level"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void missingProjectFileFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                tempDir.resolve("game/project.godot").toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("does not exist"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void emptyProjectSourceSetFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("no sources to compile"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void outputInsideProjectResourceTreeFailsBeforeModuleCreation(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("game/build").toString(),
                projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("outside the project resource tree"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void outputCollidingWithExistingFileIsUsageErrorBeforeModule(@TempDir Path tempDir)
            throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var occupied = writeFile(tempDir.resolve("occupied"), "x\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", occupied.toString(), projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("non-directory"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void mismatchedProjectOptionFailsWhileMatchIsAccepted(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        var otherProject = writeProject(tempDir.resolve("other"), "Other", "");
        writeFile(tempDir.resolve("game/a.gd3"), validSource("A"));
        var mismatched = new Terminal();
        var matched = new Terminal();

        var mismatchedExit = mismatched.command().commandLine().execute(
                "--project", otherProject.toString(),
                "-o", tempDir.resolve("build/mismatch").toString(),
                projectFile.toString());
        var matchedExit = matched.command().commandLine().execute(
                "--project", projectFile.toString(),
                "-o", tempDir.resolve("build/match").toString(),
                projectFile.toString());

        assertEquals(GdccCommand.EXIT_USAGE, mismatchedExit);
        assertTrue(mismatched.errText().contains("same project.godot"), mismatched.errText());
        assertTrue(mismatched.api.listModules().isEmpty());

        // Matching --project is accepted but emits no sidecar .gdextension in project mode.
        assertEquals(0, matchedExit, matched.errText());
        assertFalse(Files.exists(tempDir.resolve("build/match/match.gdextension")));
        assertFalse(matched.outText().contains("Metadata:"), matched.outText());
    }

    @Test
    void frontendFailureInCollectedSourcesIsCompileFailureNotUsageError(@TempDir Path tempDir) throws IOException {
        var projectFile = writeProject(tempDir.resolve("game"), "Game", "");
        writeFile(tempDir.resolve("game/broken.gd3"), "this is not gdscript (((\n");
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                projectFile.toString());

        assertEquals(GdccCommand.EXIT_COMPILE_FAILED, exitCode);
        assertTrue(terminal.errText().contains("Compile failed"), terminal.errText());
        assertEquals(List.of("demo"), terminal.api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    private static void assertEntry(Terminal terminal, String moduleId, String virtualPath, String displayPath) {
        var entry = org.junit.jupiter.api.Assertions.assertInstanceOf(
                VfsEntrySnapshot.FileEntrySnapshot.class, terminal.api.readEntry(moduleId, virtualPath));
        assertEquals(virtualPath, entry.virtualPath());
        assertEquals(displayPath, entry.path());
        assertFalse(terminal.api.readFile(moduleId, virtualPath).isBlank());
    }

    private static String singleModuleId(Terminal terminal) {
        var modules = terminal.api.listModules();
        assertEquals(1, modules.size());
        return modules.getFirst().moduleId();
    }

    private static Path writeProject(Path root, String name, String extra) throws IOException {
        var projectFile = root.resolve("project.godot");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile,
                "config_version=5\n\n[application]\nconfig/name=\"" + name + "\"\n" + extra,
                StandardCharsets.UTF_8);
        return projectFile;
    }

    private static Path writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted

                func value() -> int:
                    return 3
                """.formatted(className);
    }
}
