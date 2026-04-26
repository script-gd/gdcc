package gd.script.gdcc.cli;

import gd.script.gdcc.util.GdccVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccCommandOptionTest {
    @Test
    void commandClassUsesDeclarativePicocliAnnotations() throws NoSuchFieldException {
        assertNotNull(GdccCommand.class.getAnnotation(Command.class));
        assertNotNull(GdccCommand.class.getDeclaredField("files").getAnnotation(Parameters.class));

        var optionFields = Arrays.stream(GdccCommand.class.getDeclaredFields())
                .filter(field -> field.getAnnotation(Option.class) != null)
                .map(field -> field.getAnnotation(Option.class))
                .flatMap(option -> Arrays.stream(option.names()))
                .toList();

        assertEquals(
                Set.of("-o", "--output", "--prefix", "--class-map", "--gde", "--opt", "--optimize", "--target", "-v", "--verbose"),
                Set.copyOf(optionFields)
        );
    }

    @Test
    void helpTextListsCliContractSurface() {
        var terminal = new Terminal();
        var exitCode = terminal.command().commandLine().execute("--help");

        assertEquals(0, exitCode);
        terminal.assertNoErr();

        var help = terminal.outText();
        assertTrue(help.contains("files"), help);
        assertTrue(help.contains("-o, --output"), help);
        assertTrue(help.contains("Defaults to input filenames"), help);
        assertTrue(help.contains("--prefix"), help);
        assertTrue(help.contains("--class-map"), help);
        assertTrue(help.contains("--gde"), help);
        assertTrue(help.contains("--opt, --optimize"), help);
        assertTrue(help.contains("--target"), help);
        assertTrue(help.contains("-v, --verbose"), help);
        assertTrue(help.contains("-V, --version"), help);
    }

    @Test
    void versionTextUsesBuildMetadataAndSkipsCompileTask() {
        var terminal = new Terminal();
        var exitCode = terminal.command().commandLine().execute("--version");

        assertEquals(0, exitCode);
        assertEquals(GdccVersion.displayText() + System.lineSeparator(), terminal.outText());
        terminal.assertNoErr();
        terminal.assertCommandBodyDidNotRun();
    }

    @Test
    void missingOutputOptionIsParsedAsDefaultOutputRequest() {
        var command = new Terminal().command();

        command.commandLine().parseArgs("src/player.gd", "src/enemy.gd");

        assertNull(command.output);
        assertEquals(List.of(Path.of("src/player.gd"), Path.of("src/enemy.gd")), command.files);
    }

    @Test
    void missingInputFilesFailBeforeCommandBodyRuns() {
        var terminal = new Terminal();
        var exitCode = terminal.command().commandLine().execute("-o", "build/demo");

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Missing required parameter"), terminal.errText());
        assertTrue(terminal.errText().contains("files"), terminal.errText());
        terminal.assertCommandBodyDidNotRun();
    }

    @Test
    void repeatableOptionsAndVerbosityCountAreParsed() {
        var command = new Terminal().command();

        command.commandLine().parseArgs(
                "-vv",
                "--class-map", "Player=RuntimePlayer",
                "--class-map", "Enemy=RuntimeEnemy",
                "--prefix", "Game_",
                "--gde", "4.5.1",
                "--opt", "release",
                "--target", "WEB_WASM32",
                "-o", "build/demo",
                "src/player.gd",
                "src/enemy.gd"
        );

        assertEquals(2, command.verbosityLevel());
        assertEquals(List.of("Player=RuntimePlayer", "Enemy=RuntimeEnemy"), command.classMaps);
        assertEquals("Game_", command.prefix);
        assertEquals("4.5.1", command.gde);
        assertEquals("release", command.opt);
        assertEquals("WEB_WASM32", command.target);
        assertEquals(Path.of("build/demo"), command.output);
        assertEquals(List.of(Path.of("src/player.gd"), Path.of("src/enemy.gd")), command.files);
    }

    @Test
    void validInvocationRunsCompileTask(@TempDir Path tempDir) throws IOException {
        var source = tempDir.resolve("player.gd");
        Files.writeString(source, """
                class_name Player
                extends RefCounted
                
                func value() -> int:
                    return 3
                """, StandardCharsets.UTF_8);
        var terminal = new Terminal();
        var exitCode = terminal.command().commandLine().execute("-o", tempDir.resolve("build/demo").toString(), source.toString());

        assertEquals(0, exitCode);
        assertTrue(terminal.outText().contains("Compiled module demo"), terminal.outText());
    }

    private static final class Terminal {
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();

        GdccCommand command() {
            return new GdccCommand(
                    CliCompileTestSupport.newApi(CliCompileTestSupport.TestCompiler.succeeding()),
                    new PrintWriter(out, true),
                    new PrintWriter(err, true)
            );
        }

        String outText() {
            return out.toString();
        }

        String errText() {
            return err.toString();
        }

        void assertNoErr() {
            assertEquals("", errText());
        }

        void assertCommandBodyDidNotRun() {
            assertFalse(errText().contains("Compile failed:"), errText());
            assertFalse(outText().contains("Compiled module"), outText());
        }
    }
}
