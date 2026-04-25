package dev.superice.gdcc.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccCommandMappingTest {
    @Test
    void prefixMapsFilenameDerivedTopLevelClassAndResultKeepsMap(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--prefix", "Game_",
                source.toString()
        );

        assertEquals(0, exitCode);
        assertEquals(Map.of("Player", "Game_Player"), terminal.api.getTopLevelCanonicalNameMap("demo"));
        assertEquals(Map.of("Player", "Game_Player"), terminal.api.getLastCompileResult("demo").topLevelCanonicalNameMap());
    }

    @Test
    void explicitClassMapCanBeProvidedWithoutPrefix(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--class-map", "Player=RuntimePlayer",
                source.toString()
        );

        assertEquals(0, exitCode);
        assertEquals(Map.of("Player", "RuntimePlayer"), terminal.api.getTopLevelCanonicalNameMap("demo"));
    }

    @Test
    void explicitClassMapOverridesPrefixGeneratedMappingForSameSourceName(@TempDir Path tempDir) throws IOException {
        var player = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var enemy = writeSource(tempDir.resolve("enemy.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--prefix", "Game_",
                "--class-map", "Player=CustomPlayer",
                player.toString(),
                enemy.toString()
        );

        assertEquals(0, exitCode);
        assertEquals(
                List.of(Map.entry("Enemy", "Game_Enemy"), Map.entry("Player", "CustomPlayer")),
                terminal.api.getTopLevelCanonicalNameMap("demo").entrySet().stream().toList()
        );
        assertEquals(
                List.of(Map.entry("Enemy", "Game_Enemy"), Map.entry("Player", "CustomPlayer")),
                terminal.api.getLastCompileResult("demo").topLevelCanonicalNameMap().entrySet().stream().toList()
        );
    }

    @Test
    void prefixDoesNotMapExplicitClassNameStatement(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validSource("Hero"));
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--prefix", "Game_",
                source.toString()
        );

        assertEquals(0, exitCode);
        assertEquals(Map.of(), terminal.api.getTopLevelCanonicalNameMap("demo"));
    }

    @Test
    void duplicateExplicitClassMapKeysFailBeforeCompileTaskStarts(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--class-map", "Player=RuntimePlayer",
                "--class-map", "Player=OtherPlayer",
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Duplicate --class-map source name: Player"), terminal.errText());
        assertEquals(0, terminal.compiler.invocationCount());
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void malformedClassMapSyntaxFailsBeforeCompileTaskStarts(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--class-map", "Player=Runtime=Player",
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("--class-map must use Source=Canonical syntax"), terminal.errText());
        assertEquals(0, terminal.compiler.invocationCount());
        assertTrue(terminal.api.listModules().isEmpty());
    }

    @Test
    void invalidGeneratedMappingNameUsesFrontendContractMessage(@TempDir Path tempDir) throws IOException {
        var source = writeSource(tempDir.resolve("player.gd"), validDefaultSource());
        var terminal = new Terminal();

        var exitCode = terminal.command().commandLine().execute(
                "-o", tempDir.resolve("build/demo").toString(),
                "--prefix", "Game__sub__",
                source.toString()
        );

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(
                terminal.errText().contains("topLevelCanonicalNameMap value must not contain reserved gdcc class-name sequence '__sub__'"),
                terminal.errText()
        );
        assertEquals(0, terminal.compiler.invocationCount());
    }

    private static Path writeSource(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source, StandardCharsets.UTF_8);
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
        private final CliCompileTestSupport.TestCompiler compiler = CliCompileTestSupport.TestCompiler.succeeding();
        private final dev.superice.gdcc.api.API api = CliCompileTestSupport.newApi(compiler);
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();

        GdccCommand command() {
            return new GdccCommand(api, new PrintWriter(out, true), new PrintWriter(err, true));
        }

        String errText() {
            return err.toString();
        }
    }
}
