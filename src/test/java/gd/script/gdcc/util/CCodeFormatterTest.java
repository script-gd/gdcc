package gd.script.gdcc.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CCodeFormatterTest {
    // jAstyle emits each line through PrintWriter.println (platform line separator), so the
    // formatter must normalize to LF to keep generated sources byte-identical across platforms.
    @Test
    public void formatNormalizesPlatformLineSeparatorToLf() {
        var formatted = CCodeFormatter.format("static void f(int* p) {\n    int x = 1;\n}\n");

        assertFalse(formatted.contains("\r"), formatted);
        assertTrue(formatted.contains("p)\n{"), formatted);
    }

    @Test
    public void formatAcceptsCrlfInputAndStillEmitsLf() {
        var formatted = CCodeFormatter.format("static void f(int* p) {\r\n    int x = 1;\r\n}\r\n");

        assertFalse(formatted.contains("\r"), formatted);
        assertTrue(formatted.contains("p)\n{"), formatted);
    }

    @Test
    public void formatKeepsEscapedCrLfInsideStringLiterals() {
        var formatted = CCodeFormatter.format("const char* s = \"a\\r\\nb\";\n");

        assertTrue(formatted.contains("\"a\\r\\nb\""), formatted);
    }
}
