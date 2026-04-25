package dev.superice.gdcc.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleOutputUtilTest {
    @Test
    void writerUsesTargetStreamCharsetAndEscapesUnmappableCodePoints() {
        var outputBuffer = new ByteArrayOutputStream();
        var asciiStream = new PrintStream(outputBuffer, true, StandardCharsets.US_ASCII);
        var writer = ConsoleOutputUtil.writerFor(asciiStream);

        writer.println("ascii ok, cjk 中, emoji 🙂");
        writer.flush();

        assertEquals(
                "ascii ok, cjk \\u4E2D, emoji \\U0001F642" + System.lineSeparator(),
                outputBuffer.toString(StandardCharsets.US_ASCII)
        );
    }

    @Test
    void utf8TargetKeepsRepresentableTextUnchanged() {
        var outputBuffer = new ByteArrayOutputStream();
        var utf8Stream = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);

        ConsoleOutputUtil.println(utf8Stream, "plain 中 🙂");

        assertEquals("plain 中 🙂" + System.lineSeparator(), outputBuffer.toString(StandardCharsets.UTF_8));
    }
}
