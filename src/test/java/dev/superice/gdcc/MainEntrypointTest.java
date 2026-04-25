package dev.superice.gdcc;

import dev.superice.gdcc.util.GdccVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainEntrypointTest {
    @Test
    void runDelegatesToPicocliHelpWithoutCompileOrchestration() {
        var result = runWithCapturedStreams("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.outText().contains("Usage: gdcc"));
        assertEquals("", result.errText());
    }

    @Test
    void runDelegatesToPicocliVersionWithoutCompileOrchestration() {
        var result = runWithCapturedStreams("--version");

        assertEquals(0, result.exitCode());
        assertEquals(GdccVersion.displayText() + System.lineSeparator(), result.outText());
        assertEquals("", result.errText());
    }

    private static CapturedRun runWithCapturedStreams(String... args) {
        var originalOut = System.out;
        var originalErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var exitCode = Main.run(args);
            return new CapturedRun(
                    exitCode,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CapturedRun(int exitCode, String outText, String errText) {
    }
}
