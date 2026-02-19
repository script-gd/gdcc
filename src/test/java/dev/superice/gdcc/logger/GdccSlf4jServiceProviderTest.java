package dev.superice.gdcc.logger;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdccSlf4jServiceProviderTest {
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*m");

    @Test
    void shouldUseEmbeddedProviderAsDefault() {
        var logger = LoggerFactory.getLogger("gdcc.provider.test");
        assertInstanceOf(GdccLogger.class, logger);
    }

    @Test
    void shouldPrintWarnLineWithExpectedFormatAndColors() {
        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;

        try {
            System.setOut(capture);
            var logger = LoggerFactory.getLogger("gdcc.format.test");
            logger.warn("hello {}", "world");
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var lines = outputBuffer.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .toList();
        var firstLine = lines.getFirst();
        var plainLine = ANSI_PATTERN.matcher(firstLine).replaceAll("");

        assertTrue(Pattern.compile("^\\u001B\\[36m\\d{2}:\\d{2}:\\d{2}\\u001B\\[39m\\u001B\\[0m .*$").matcher(firstLine).matches());
        assertTrue(firstLine.contains("\u001B[33m[WARNING]\u001B[39m\u001B[0m"));
        assertTrue(firstLine.contains("\u001B[37m\u001B[2m[gdcc.format.test]\u001B[22m\u001B[39m\u001B[0m"));
        assertTrue(plainLine.matches("^\\d{2}:\\d{2}:\\d{2} \\[WARNING] \\[gdcc\\.format\\.test] hello world$"));
    }
}
