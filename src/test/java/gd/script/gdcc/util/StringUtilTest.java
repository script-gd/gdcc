package gd.script.gdcc.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
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
        // Godot 4.5.1 escape set: \a \b \f \v \' complete the classic \n \r \t \\ \" set.
        assertEquals("\u0007\b\f\u000B'", StringUtil.unescapeQuoted("\\a\\b\\f\\v\\'"));
        assertEquals("A", StringUtil.unescapeQuoted("\\u0041"));
        // `\U` takes exactly 6 hex digits (Godot 4.5.1), not 8.
        assertEquals("\uD83D\uDE00", StringUtil.unescapeQuoted("\\U01F600"));
        // Lead/trail surrogate escapes combine into a single code point.
        assertEquals("\uD83D\uDE00", StringUtil.unescapeQuoted("\\uD83D\\uDE00"));
        // Backslash-newline is a line continuation and contributes nothing.
        assertEquals("ab", StringUtil.unescapeQuoted("a\\\nb"));
        assertEquals("ab", StringUtil.unescapeQuoted("a\\\r\nb"));
        // A lone carriage return after a backslash keeps the backslash and drops the CR (Godot
        // parity); unlike Godot we deliberately do not also drop the following character.
        assertEquals("a\\b", StringUtil.unescapeQuoted("a\\\rb"));
    }

    @Test
    public void unescapeQuotedRejectsMalformedEscapes() {
        var shortUnicode = assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\u123"));
        assertEquals("Invalid unicode escape in literal: \\u", shortUnicode.getMessage());

        var shortCodePoint = assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\U12345"));
        assertEquals("Invalid unicode escape in literal: \\U", shortCodePoint.getMessage());

        // Unknown escapes are hard errors (Godot parity) instead of silently dropping the backslash.
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\q"));
        // Invalid hex digits in a unicode escape.
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\u12G4"));
        // Code points above U+10FFFF are invalid.
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\U110000"));
        // Unpaired UTF-16 surrogates are invalid in both directions.
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\uD83D\\u0041"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("\\uDE00"));
        // A lone trailing backslash cannot start an escape.
        assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeQuoted("a\\"));
    }

    @Test
    public void decodeGdStringLexemeNormalizesStringAndStringNamePayloads() {
        assertAll(
                () -> assertEquals("hello", StringUtil.decodeGdStringLexeme("\"hello\"")),
                () -> assertEquals("hello", StringUtil.decodeGdStringLexeme("'hello'")),
                () -> assertEquals("line\nbreak", StringUtil.decodeGdStringLexeme("\"line\\nbreak\"")),
                () -> assertEquals("tab\tquote\"", StringUtil.decodeGdStringLexeme("\"tab\\tquote\\\"\"")),
                () -> assertEquals("tab\tquote\"", StringUtil.decodeGdStringLexeme("'tab\\tquote\\\"'")),
                () -> assertEquals("Node_2D", StringUtil.decodeGdStringLexeme("&\"Node_2D\"")),
                () -> assertEquals("A", StringUtil.decodeGdStringLexeme("\"\\u0041\"")),
                () -> assertEquals("\uD83D\uDE00", StringUtil.decodeGdStringLexeme("\"\\U01F600\"")),
                // Triple-quoted multiline payloads keep raw newlines.
                () -> assertEquals("line1\nline2", StringUtil.decodeGdStringLexeme("\"\"\"line1\nline2\"\"\"")),
                () -> assertEquals("it's", StringUtil.decodeGdStringLexeme("'''it's'''")),
                () -> assertEquals("", StringUtil.decodeGdStringLexeme("\"\"\"\"\"\""))
        );
    }

    @Test
    public void decodeGdStringLexemeKeepsRawPayloadsVerbatim() {
        assertAll(
                // Raw strings never interpret escapes; `\<quote>` and `\\` pairs stay verbatim too.
                () -> assertEquals("\\n", StringUtil.decodeGdStringLexeme("r\"\\n\"")),
                () -> assertEquals("a\\\"b", StringUtil.decodeGdStringLexeme("r\"a\\\"b\"")),
                () -> assertEquals("a\\\\b", StringUtil.decodeGdStringLexeme("r'a\\\\b'")),
                () -> assertEquals("C:\\path\\file.png", StringUtil.decodeGdStringLexeme("r\"C:\\path\\file.png\"")),
                () -> assertEquals("x\ny", StringUtil.decodeGdStringLexeme("r\"\"\"x\ny\"\"\""))
        );
    }

    @Test
    public void decodeGdStringLexemeRejectsMalformedLexemes() {
        assertAll(
                () -> assertMalformedLexeme("\"unterminated"),
                () -> assertMalformedLexeme("'unterminated"),
                () -> assertMalformedLexeme("\"\"\"unterminated"),
                () -> assertMalformedLexeme("r\"unterminated"),
                () -> assertMalformedLexeme("r'\""),
                () -> assertMalformedLexeme("&hello"),
                () -> assertMalformedLexeme("&\""),
                () -> assertMalformedLexeme("&'name'"),
                () -> assertMalformedLexeme("plain"),
                // Escape-level failures surface through the same entry point.
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.decodeGdStringLexeme("\"\\q\""))
        );
    }

    @Test
    public void decodeNodePathLexemeNormalizesPayload() {
        assertAll(
                () -> assertEquals("a/b", StringUtil.decodeNodePathLexeme("^\"a/b\"")),
                () -> assertEquals("a\"b", StringUtil.decodeNodePathLexeme("^\"a\\\"b\"")),
                () -> assertEquals("A", StringUtil.decodeNodePathLexeme("^\"\\u0041\"")),
                () -> assertEquals("", StringUtil.decodeNodePathLexeme("^\"\""))
        );
    }

    @Test
    public void decodeNodePathLexemeRejectsMalformedLexemes() {
        assertAll(
                // Missing `^` prefix, missing quotes, and unterminated quoting all fail fast.
                () -> assertMalformedNodePathLexeme("\"a/b\""),
                () -> assertMalformedNodePathLexeme("^a/b"),
                () -> assertMalformedNodePathLexeme("^\""),
                () -> assertMalformedNodePathLexeme("^\"unterminated"),
                () -> assertMalformedNodePathLexeme("&\"name\""),
                () -> assertMalformedNodePathLexeme("plain"),
                () -> assertMalformedNodePathLexeme("")
        );
    }

    @Test
    public void decodeGetNodePathLexemeNormalizesShorthandForms() {
        assertAll(
                () -> assertEquals("Camera3D", StringUtil.decodeGetNodePathLexeme("$Camera3D")),
                () -> assertEquals("A B", StringUtil.decodeGetNodePathLexeme("$\"A B\"")),
                () -> assertEquals("/root/X", StringUtil.decodeGetNodePathLexeme("$/root/X")),
                // `%` survives decoding: the runtime unique-name lookup needs the prefix.
                () -> assertEquals("%Foo", StringUtil.decodeGetNodePathLexeme("%Foo")),
                () -> assertEquals("%A B", StringUtil.decodeGetNodePathLexeme("%\"A B\"")),
                () -> assertEquals("a\"b", StringUtil.decodeGetNodePathLexeme("$\"a\\\"b\""))
        );
    }

    @Test
    public void decodeGetNodePathLexemeRejectsMalformedLexemes() {
        assertAll(
                () -> assertMalformedGetNodeLexeme("$"),
                () -> assertMalformedGetNodeLexeme("%"),
                () -> assertMalformedGetNodeLexeme("$\""),
                () -> assertMalformedGetNodeLexeme("$\"unterminated"),
                () -> assertMalformedGetNodeLexeme("%\"unterminated"),
                () -> assertMalformedGetNodeLexeme("plain"),
                () -> assertMalformedGetNodeLexeme("^\"a/b\""),
                () -> assertMalformedGetNodeLexeme("")
        );
    }

    private static void assertMalformedNodePathLexeme(String lexeme) {
        var error = assertThrows(IllegalArgumentException.class, () -> StringUtil.decodeNodePathLexeme(lexeme));
        assertEquals("Invalid GDScript node path lexeme: " + lexeme, error.getMessage());
    }

    private static void assertMalformedGetNodeLexeme(String lexeme) {
        var error = assertThrows(IllegalArgumentException.class, () -> StringUtil.decodeGetNodePathLexeme(lexeme));
        assertEquals("Invalid GDScript get-node lexeme: " + lexeme, error.getMessage());
    }

    private static void assertMalformedLexeme(String lexeme) {
        var error = assertThrows(IllegalArgumentException.class, () -> StringUtil.decodeGdStringLexeme(lexeme));
        assertEquals("Invalid GDScript string lexeme: " + lexeme, error.getMessage());
    }
}
