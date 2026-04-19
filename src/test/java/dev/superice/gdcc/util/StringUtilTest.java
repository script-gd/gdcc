package dev.superice.gdcc.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StringUtilTest {
    @Test
    public void requireNonBlankPreservesNonBlankInput() {
        var value = "  value  ";

        assertSame(value, StringUtil.requireNonBlank(value, "value"));
    }

    @Test
    public void requireNonBlankRejectsNullAndBlank() {
        var nullError = assertThrows(NullPointerException.class, () -> StringUtil.requireNonBlank(null, "value"));
        assertEquals("value must not be null", nullError.getMessage());

        var blankError = assertThrows(
                IllegalArgumentException.class,
                () -> StringUtil.requireNonBlank(" \t ", "value")
        );
        assertEquals("value must not be blank", blankError.getMessage());
    }

    @Test
    public void requireNullableNonBlankAllowsNullButRejectsBlank() {
        assertNull(StringUtil.requireNullableNonBlank(null, "value"));
        assertEquals("hello", StringUtil.requireNullableNonBlank("hello", "value"));

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> StringUtil.requireNullableNonBlank("   ", "value")
        );
        assertEquals("value must not be blank", error.getMessage());
    }

    @Test
    public void requireTrimmedNonBlankReturnsTrimmedValue() {
        assertEquals("hello", StringUtil.requireTrimmedNonBlank("  hello  ", "value"));

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> StringUtil.requireTrimmedNonBlank(" \n ", "value")
        );
        assertEquals("value must not be blank", error.getMessage());
    }

    @Test
    public void trimHelpersNormalizeNullableText() {
        assertEquals("", StringUtil.trimToEmpty(null));
        assertEquals("hello", StringUtil.trimToEmpty("  hello  "));
        assertNull(StringUtil.trimToNull(null));
        assertNull(StringUtil.trimToNull("   "));
        assertEquals("hello", StringUtil.trimToNull("  hello  "));
    }

    @Test
    public void normalizeIndentedSnippetAndSplitLinesNormalizeMultilineText() {
        var raw = "\r\n    alpha\r\n      beta\r\n\r\n";
        var normalized = StringUtil.normalizeIndentedSnippet(raw);

        assertEquals("alpha\n      beta", normalized);
        assertEquals(List.of("alpha", "      beta"), StringUtil.splitLines(normalized));
    }

    @Test
    public void unescapeQuotedHandlesEscapesAndUnicode() {
        assertEquals("line\nbreak", StringUtil.unescapeQuoted("line\\nbreak"));
        assertEquals("tab\tquote\"", StringUtil.unescapeQuoted("tab\\tquote\\\""));
        assertEquals("A", StringUtil.unescapeQuoted("\\u0041"));
        assertEquals("\uD83D\uDE00", StringUtil.unescapeQuoted("\\U0001F600"));
    }

    @Test
    public void unescapeQuotedRejectsMalformedUnicodeEscape() {
        var shortUnicode = assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\u123"));
        assertEquals("Invalid unicode escape in literal: \\u", shortUnicode.getMessage());

        var shortCodePoint = assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\U1234567"));
        assertEquals("Invalid unicode escape in literal: \\U", shortCodePoint.getMessage());
    }
}
