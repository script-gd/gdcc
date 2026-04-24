package dev.superice.gdcc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainEntrypointTest {
    @Test
    void runDelegatesToPicocliHelpWithoutCompileOrchestration() {
        var originalOut = System.out;
        var originalErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var exitCode = Main.run(new String[]{"--help"});

            assertEquals(0, exitCode);
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: gdcc"));
            assertEquals("", err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
