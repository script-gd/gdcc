package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        assertEquals(Set.of("-o", "--output", "--prefix", "--class-map", "--gde", "-v", "--verbose"), Set.copyOf(optionFields));
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
        assertTrue(help.contains("--prefix"), help);
        assertTrue(help.contains("--class-map"), help);
        assertTrue(help.contains("--gde"), help);
        assertTrue(help.contains("-v, --verbose"), help);
    }

    @Test
    void missingOutputOptionFailsBeforeCommandBodyRuns() {
        var terminal = new Terminal();
        var exitCode = terminal.command().commandLine().execute("src/player.gd");

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("Missing required option"), terminal.errText());
        assertTrue(terminal.errText().contains("-o=<output>"), terminal.errText());
        terminal.assertCommandBodyDidNotRun();
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
                "-o", "build/demo",
                "src/player.gd",
                "src/enemy.gd"
        );

        assertEquals(2, command.verbosityLevel());
        assertEquals(List.of("Player=RuntimePlayer", "Enemy=RuntimeEnemy"), command.classMaps);
        assertEquals("Game_", command.prefix);
        assertEquals("4.5.1", command.gde);
        assertEquals(Path.of("build/demo"), command.output);
        assertEquals(List.of(Path.of("src/player.gd"), Path.of("src/enemy.gd")), command.files);
    }

    @Test
    void validStepOneInvocationReachesDocumentedTemporaryBoundary() {
        var terminal = new Terminal();
        var exitCode = terminal.command().commandLine().execute("-o", "build/demo", "src/player.gd");

        assertEquals(GdccCommand.EXIT_USAGE, exitCode);
        assertTrue(terminal.errText().contains("compile pipeline is not implemented yet"), terminal.errText());
    }

    private static final class Terminal {
        private final StringWriter out = new StringWriter();
        private final StringWriter err = new StringWriter();

        GdccCommand command() {
            return new GdccCommand(new API(), new PrintWriter(out, true), new PrintWriter(err, true));
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
            assertFalse(errText().contains("compile pipeline is not implemented yet"), errText());
        }
    }
}
