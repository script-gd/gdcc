package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import dev.superice.gdcc.api.CompileOptions;
import dev.superice.gdcc.api.ModuleSnapshot;
import dev.superice.gdcc.api.VfsEntrySnapshot;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccCommandInputTest {
    private static final List<Path> DEFAULT_OUTPUT_BUILD_DIRECTORIES = List.of(
            Path.of("player_enemy"),
            Path.of("main_main")
    );

    @AfterEach
    void cleanDefaultOutputBuildDirectories() throws IOException {
        for (var directory : DEFAULT_OUTPUT_BUILD_DIRECTORIES) {
            deleteDirectoryIfExists(directory);
        }
    }

    @Test
    void loadsInputFilesIntoOneModuleWithStableVirtualPathsAndDisplayPaths(@TempDir Path tempDir) throws IOException {
        var firstSource = writeSource(tempDir.resolve("one/main.gd"), validSource("FirstMain"));
        var secondSource = writeSource(tempDir.resolve("two/main.gd"), validSource("SecondMain"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                firstSource.toString(),
                secondSource.toString()
        );

        assertEquals(0, exitCode);
        assertEquals(List.of("demo"), terminal.api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
        assertEquals(List.of("0000", "0001"), terminal.api.listDirectory("demo", "/src").stream()
                .map(VfsEntrySnapshot::name)
                .toList());

        assertSourceFile(terminal.api, "/src/0000/main.gd", firstSource.toString(), validSource("FirstMain"));
        assertSourceFile(terminal.api, "/src/0001/main.gd", secondSource.toString(), validSource("SecondMain"));
        assertEquals(List.of(firstSource.toString(), secondSource.toString()), terminal.api.getLastCompileResult("demo").sourcePaths());
    }

    @Test
    void missingOutputUsesInputFileStemsAsModuleTarget(@TempDir Path tempDir) throws IOException {
        var playerSource = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var enemySource = writeSource(tempDir.resolve("enemy.gd"), validSource("Enemy"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(playerSource.toString(), enemySource.toString());

        assertEquals(0, exitCode);
        assertEquals(List.of("player_enemy"), terminal.api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
        assertEquals(Path.of("player_enemy").toAbsolutePath().normalize(),
                terminal.api.getCompileOptions("player_enemy").projectPath());
        assertEquals(List.of(playerSource.toString(), enemySource.toString()),
                CliCompileTestSupport.awaitLastResult(terminal.api, "player_enemy").sourcePaths());
    }

    @Test
    void missingOutputKeepsRepeatedInputFileStems(@TempDir Path tempDir) throws IOException {
        var firstSource = writeSource(tempDir.resolve("one/main.gd"), validSource("FirstMain"));
        var secondSource = writeSource(tempDir.resolve("two/main.gd"), validSource("SecondMain"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(firstSource.toString(), secondSource.toString());

        assertEquals(0, exitCode);
        assertEquals(List.of("main_main"), terminal.api.listModules().stream().map(ModuleSnapshot::moduleId).toList());
        assertEquals(Path.of("main_main").toAbsolutePath().normalize(),
                terminal.api.getCompileOptions("main_main").projectPath());
    }

    @Test
    void duplicateHostFileArgumentsStayDistinctInVirtualFileSystem(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                source.toString(),
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_COMPILE_FAILED, exitCode);
        assertTrue(terminal.errText().contains("Compile failed: FRONTEND_FAILED"), terminal.errText());
        assertSourceFile(terminal.api, "/src/0000/player.gd", source.toString(), validDefaultSource());
        assertSourceFile(terminal.api, "/src/0001/player.gd", source.toString(), validDefaultSource());
    }

    @Test
    void missingInputFileFailsBeforeCreatingModule(@TempDir Path tempDir) throws IOException {
        var existingSource = writeSource(tempDir.resolve("player.gd"), validSource("ExistingPlayer"));
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
        terminal.assertCompilerNotInvoked();
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
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void nonGdInputFileFailsBeforeCreatingModule(@TempDir Path tempDir) throws IOException {
        var textFile = writeSource(tempDir.resolve("player.txt"), validSource("Player"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                textFile.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Input file must use .gd extension"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void invalidOutputPathFailsBeforeCreatingModule(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var blankOutput = new Terminal();
        var rootOutput = new Terminal();

        var blankExitCode = blankOutput.command().commandLine().execute("-o", "", source.toString());
        var rootExitCode = rootOutput.command().commandLine().execute("-o", tempDir.getRoot().toString(), source.toString());

        assertEquals(GdccCommand.EXIT_USAGE, blankExitCode);
        assertTrue(blankOutput.errText().contains("Output path must not be blank"), blankOutput.errText());
        blankOutput.assertCompilerNotInvoked();
        assertTrue(blankOutput.api.listModules().isEmpty());

        assertEquals(GdccCommand.EXIT_USAGE, rootExitCode);
        assertTrue(rootOutput.errText().contains("Output path must include a module name"), rootOutput.errText());
        rootOutput.assertCompilerNotInvoked();
        assertTrue(rootOutput.api.listModules().isEmpty());
    }

    @Test
    void apiModuleCreationFailureIsRenderedAsUsageError(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal();
        terminal.api.createModule("demo", "Existing Demo");

        var exitCode = terminal.command().commandLine().execute(
                "-o",
                tempDir.resolve("build/demo").toString(),
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Module 'demo' already exists"), terminal.errText());
        terminal.assertCompilerNotInvoked();
        assertEquals(0, terminal.api.getModule("demo").rootEntryCount());
    }

    @Test
    void setsCompileOptionsFromOutputTargetAndGdeVersion(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "--gde",
                "4.5.1",
                "-o",
                tempDir.resolve("build/demo").toString(),
                source.toString()
        );

        assertEquals(0, exitCode);
        var options = terminal.api.getCompileOptions("demo");
        assertEquals(GodotVersion.V451, options.godotVersion());
        assertEquals(tempDir.resolve("build/demo").toAbsolutePath().normalize(), options.projectPath());
        assertDefaultNonCliCompileOptions(options);
    }

    @Test
    void outputWithoutParentUsesNamedDirectoryUnderCurrentWorkingDirectoryBeforeTaskCreation(@TempDir Path tempDir)
            throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", "demo",
                "--class-map", "Player=Runtime__sub__Player",
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("reserved gdcc class-name sequence"), terminal.errText());
        assertEquals(Path.of("demo").toAbsolutePath().normalize(), terminal.api.getCompileOptions("demo").projectPath());
        terminal.assertCompilerNotInvoked();
    }

    @Test
    void outputTargetsUnderSharedParentUseIndependentHostBuildDirectories(@TempDir Path tempDir)
            throws IOException {
        var playerSource = writeSource(tempDir.resolve("player.gd"), validSource("Player"));
        var enemySource = writeSource(tempDir.resolve("enemy.gd"), validSource("Enemy"));
        var terminal = new Terminal();

        var playerExitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/player").toString(),
                playerSource.toString()
        );
        var enemyExitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/enemy").toString(),
                enemySource.toString()
        );

        assertEquals(0, playerExitCode);
        assertEquals(0, enemyExitCode);
        assertEquals(tempDir.resolve("build/player").toAbsolutePath().normalize(),
                terminal.api.getCompileOptions("player").projectPath());
        assertEquals(tempDir.resolve("build/enemy").toAbsolutePath().normalize(),
                terminal.api.getCompileOptions("enemy").projectPath());
        assertEquals(
                List.of(
                        tempDir.resolve("build/player/entry.c").toAbsolutePath().normalize(),
                        tempDir.resolve("build/player/engine_method_binds.h").toAbsolutePath().normalize(),
                        tempDir.resolve("build/player/entry.h").toAbsolutePath().normalize()
                ),
                terminal.api.getLastCompileResult("player").generatedFiles()
        );
        assertEquals(
                List.of(
                        tempDir.resolve("build/enemy/entry.c").toAbsolutePath().normalize(),
                        tempDir.resolve("build/enemy/engine_method_binds.h").toAbsolutePath().normalize(),
                        tempDir.resolve("build/enemy/entry.h").toAbsolutePath().normalize()
                ),
                terminal.api.getLastCompileResult("enemy").generatedFiles()
        );
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

    private static void deleteDirectoryIfExists(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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

    private static void assertDefaultNonCliCompileOptions(CompileOptions options) {
        assertEquals(COptimizationLevel.DEBUG, options.optimizationLevel());
        assertEquals(TargetPlatform.getNativePlatform(), options.targetPlatform());
        assertFalse(options.strictMode());
        assertEquals(CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, options.outputMountRoot());
    }

    private static final class Terminal {
        private final CliCompileTestSupport.TestCompiler compiler = CliCompileTestSupport.TestCompiler.succeeding();
        private final API api = CliCompileTestSupport.newApi(compiler);
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();

        GdccCommand command() {
            return new GdccCommand(api, new PrintWriter(out, true), new PrintWriter(err, true));
        }

        String errText() {
            return err.toString();
        }

        void assertCompilerNotInvoked() {
            assertEquals(0, compiler.invocationCount());
        }
    }
}
