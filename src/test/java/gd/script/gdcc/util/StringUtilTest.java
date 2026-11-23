package gd.script.gdcc.util;

import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StringUtilTest {
    private static final Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void gdWhitespaceStopsAtTheFirstNonWhitespaceOrIncompleteLineBreak() {
        var text = "x \t\r\n\nz";
        assertEquals(6, StringUtil.skipGdWhitespace(text, 1, text.length()));
        assertEquals(3, StringUtil.skipGdWhitespace(text, 1, 4));
        assertEquals(1, StringUtil.skipGdWhitespace(text, 1, 1));
        assertEquals(0, StringUtil.skipGdWhitespace("\f ", 0, 2));
        assertEquals(0, StringUtil.skipGdWhitespace("#\n", 0, 2));
        assertEquals(0, StringUtil.skipGdWhitespace("\\\n", 0, 2));
        assertTrue(StringUtil.isGdWhitespaceChar((byte) '\r'));
        assertFalse(StringUtil.isGdWhitespaceChar('\u2003'));
    }

    @Test
    void gdTriviaSkipsCommentsAndCompleteContinuationsWithinTheRange() {
        var text = "x \\\n# =\rstill comment\r\n\\\r\n =";
        var equals = text.length() - 1;
        assertEquals(equals, StringUtil.skipGdTrivia(text, 1, text.length()));
        assertEquals(equals, StringUtil.skipGdTrivia(text, 1, equals));
        assertEquals(2, StringUtil.skipGdTrivia("x \\\r\n", 1, 4));
        assertEquals(2, StringUtil.skipGdTrivia("x \\ ", 1, 4));
        assertEquals(2, StringUtil.skipGdTrivia("x \r=", 1, 4));
        assertEquals(0, StringUtil.skipGdTrivia("\"#literal\"", 0, 10));
    }

    @Test
    void gdLineCommentStopsAtLfOrTheBoundEvenWhenItContainsCr() {
        var text = "x# comment\r=\\\r\n=";
        var lineFeed = text.length() - 2;
        assertEquals(lineFeed, StringUtil.skipGdLineComment(text, 1, text.length()));
        assertEquals(lineFeed - 1, StringUtil.skipGdLineComment(text, 1, lineFeed - 1));
        assertEquals(8, StringUtil.skipGdLineComment("# tail =", 0, 8));
        assertEquals(8, StringUtil.skipGdTrivia("# tail =", 0, 8));
    }

    @Test
    public void stripAnsiRemovesPicocliStyleSequences() {
        // Real picocli rendering on ANSI-capable terminals (observed on Windows CI agents).
        assertEquals("-o, --output", StringUtil.stripAnsi("\u001B[33m-o\u001B[39m, \u001B[33m--output\u001B[39m"));
        assertEquals("Usage: gdcc", StringUtil.stripAnsi("Usage: \u001B[1mgdcc\u001B[21m\u001B[0m"));
    }

    @Test
    public void stripAnsiKeepsPlainTextUntouched() {
        assertEquals("Usage: gdcc [-hVv] files...", StringUtil.stripAnsi("Usage: gdcc [-hVv] files..."));
    }

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
    public void capitalizeFollowsGodotCompoundWordRules() {
        // Expectations mirror Godot ustring.cpp `capitalize()`: underscores/hyphens/whitespace
        // collapse to single spaces, everything lowercases, each word uppercases its head.
        assertEquals("State Idle", StringUtil.capitalize("STATE_IDLE"));
        assertEquals("Idle", StringUtil.capitalize("IDLE"));
        assertEquals("Jump", StringUtil.capitalize("Jump"));
        assertEquals("Move Left", StringUtil.capitalize("move-left"));
        assertEquals("My Enum Value 2", StringUtil.capitalize("my_enum_value2"));
    }

    @Test
    public void capitalizeSplitsGodotCaseAndDigitBoundaries() {
        // Boundary conditions a-d from `_separate_compound_words`: aA, AAa/2Aa, 2aa, A2/a2.
        assertEquals("Move Left", StringUtil.capitalize("moveLeft"));
        assertEquals("Http Server 2fa", StringUtil.capitalize("HTTPServer2FA"));
        assertEquals("Version 2 Value", StringUtil.capitalize("version2Value"));
        assertEquals("V 2 Aa", StringUtil.capitalize("v2aa"));
        assertEquals("State 2d", StringUtil.capitalize("STATE_2D"));
    }

    @Test
    public void capitalizeClassifiesUnicodeLettersLikeGodot() {
        // GDScript identifiers follow UAX#31, so Unicode letters must hit the same case
        // boundaries as Godot's char32_t implementation (nÜ is an aA boundary).
        assertEquals("Klein Übergang", StringUtil.capitalize("kleinÜbergang"));
        // Godot `is_hyphen` also covers U+2010/U+2011; `strip_edges` removes edge chars <= 32
        // (control characters included), unlike Java's Unicode-whitespace `strip()`.
        assertEquals("State Idle", StringUtil.capitalize("state‐idle"));
        assertEquals("Idle", StringUtil.capitalize("\u0001idle"));
    }

    @Test
    public void capitalizeHandlesDegenerateInputs() {
        assertEquals("", StringUtil.capitalize(""));
        assertEquals("A", StringUtil.capitalize("a"));
        assertEquals("A", StringUtil.capitalize("A"));
        // Separator-only runs collapse away instead of fabricating empty words.
        assertEquals("", StringUtil.capitalize("__"));
        assertEquals("", StringUtil.capitalize("---"));
        assertEquals("", StringUtil.capitalize("   "));
        assertEquals("Idle", StringUtil.capitalize("_idle_"));
    }

    @Test
    public void normalizeIndentedSnippetAndSplitLinesNormalizeMultilineText() {
        var raw = "\r\n    alpha\r\n      beta\r\n\r\n";
        var normalized = StringUtil.normalizeIndentedSnippet(raw);

        assertEquals("alpha\n      beta", normalized);
        assertEquals(List.of("alpha", "      beta"), StringUtil.splitLines(normalized));
    }

    @Test
    public void escapeLineDirectiveFileNameKeepsNonAsciiRawAndEscapesStructuralChars() {
        assertEquals("foo.gd", StringUtil.escapeLineDirectiveFileName("foo.gd"));
        // Raw UTF-8 is the only spelling that stays byte-identical under tcc's escape-free #line
        // parser and under clang/zig (generated sources are UTF-8).
        assertEquals("café中文.gd", StringUtil.escapeLineDirectiveFileName("café中文.gd"));
        assertEquals("dir\\\"quoted\\\".gd", StringUtil.escapeLineDirectiveFileName("dir\"quoted\".gd"));
        assertEquals("C:\\\\work\\\\main.gd", StringUtil.escapeLineDirectiveFileName("C:\\work\\main.gd"));
        // A raw tab is legal inside the quoted directive operand on both clang/zig and tcc.
        assertEquals("a\tb.gd", StringUtil.escapeLineDirectiveFileName("a\tb.gd"));
    }

    @Test
    public void escapeLineDirectiveFileNameRejectsControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> StringUtil.escapeLineDirectiveFileName("a\nb.gd"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.escapeLineDirectiveFileName("a\0b.gd"));
        assertThrows(IllegalArgumentException.class,
                () -> StringUtil.escapeLineDirectiveFileName("a" + (char) 0x7F + "b.gd"));
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
    public void escapeCUtf8BytesLiteralEmitsByteLevelOctal() {
        assertEquals("abc", StringUtil.escapeCUtf8BytesLiteral("abc"));
        assertEquals("a\\\"b\\\\c", StringUtil.escapeCUtf8BytesLiteral("a\"b\\c"));
        // Embedded NUL and control bytes become fixed three-digit octal — no `\u0000` UCN.
        assertEquals("a\\000b", StringUtil.escapeCUtf8BytesLiteral("a\0b"));
        assertEquals("\\001\\011\\012\\015\\177", StringUtil.escapeCUtf8BytesLiteral("\u0001\t\n\r\u007F"));
        // Fixed width means a trailing digit can never extend the escape (`\000` + `1`).
        assertEquals("a\\0001", StringUtil.escapeCUtf8BytesLiteral("a\0" + "1"));
        // Multi-byte UTF-8 escapes byte-by-byte; C1/C2 lead bytes included.
        assertEquals("\\303\\251", StringUtil.escapeCUtf8BytesLiteral("é"));
        assertEquals("\\344\\270\\255\\346\\226\\207", StringUtil.escapeCUtf8BytesLiteral("中文"));
        assertEquals("\\360\\237\\230\\200", StringUtil.escapeCUtf8BytesLiteral("\uD83D\uDE00"));
    }

    @Test
    public void escapeCUtf8BytesEncodesTheSameArray() {
        var utf8 = "é".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("\\303\\251", StringUtil.escapeCUtf8Bytes(utf8));
        assertEquals(2, utf8.length);
    }

    @Test
    public void escapeLirStringLiteralKeepsNamedEscapesAndEscapesXmlUnsafeChars() {
        assertEquals("a\\nb\\rc\\td\\\"e\\\\f", StringUtil.escapeLirStringLiteral("a\nb\rc\td\"e\\f"));
        // XML-1.0-forbidden controls and unpaired surrogates become fixed-width \\uXXXX.
        assertEquals("a\\u0000b", StringUtil.escapeLirStringLiteral("a\0b"));
        assertEquals("\\u0001\\u001F\\u007F\\u0085", StringUtil.escapeLirStringLiteral("\u0001\u001F\u007F\u0085"));
        assertEquals("\\uFFFE\\uFFFF", StringUtil.escapeLirStringLiteral("\uFFFE\uFFFF"));
        assertEquals("\\uD800", StringUtil.escapeLirStringLiteral("\uD800"));
        assertEquals("\\uDC00", StringUtil.escapeLirStringLiteral("\uDC00"));
        // Valid surrogate pairs and ordinary non-ASCII text stay raw.
        assertEquals("\uD83D\uDE00", StringUtil.escapeLirStringLiteral("\uD83D\uDE00"));
        assertEquals("中文", StringUtil.escapeLirStringLiteral("中文"));
        // A real backslash escapes first: literal `\u0000` text stays distinct from a real NUL.
        assertEquals("a\\\\u0000b", StringUtil.escapeLirStringLiteral("a\\u0000b"));
    }

    @Test
    public void unescapeLirStringLiteralDecodesStrictly() {
        assertEquals("a\nb\rc\td\"e\\f", StringUtil.unescapeLirStringLiteral("a\\nb\\rc\\td\\\"e\\\\f"));
        assertEquals("a\0b", StringUtil.unescapeLirStringLiteral("a\\u0000b"));
        // Exactly four hex digits: `\u0000` then literal `1`, and hex case is free.
        assertEquals("\0" + "1", StringUtil.unescapeLirStringLiteral("\\u00001"));
        assertEquals("éÉ", StringUtil.unescapeLirStringLiteral("\\u00e9\\u00C9"));
        // Raw non-escape content, including non-ASCII, passes through.
        assertEquals("中文a", StringUtil.unescapeLirStringLiteral("中文a"));
    }

    @Test
    public void unescapeLirStringLiteralRejectsMalformedEscapes() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\u")),
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\u000")),
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\u000G")),
                // `Integer.parseInt` accepts a leading sign and non-ASCII digits — all of them
                // must be rejected before parsing, not decoded into wrong characters.
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\u+041")),
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\u-001")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> StringUtil.unescapeLirStringLiteral("\\u００４１")),
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\q")),
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("\\U0001F600")),
                () -> assertThrows(IllegalArgumentException.class, () -> StringUtil.unescapeLirStringLiteral("a\\"))
        );
    }

    @Test
    public void lirStringLiteralRoundTripsThroughEscapePair() {
        for (var payload : List.of("plain", "a\0b", "quote\"and\\backslash", "\n\r\t",
                "中文é", "\uD83D\uDE00", "\u0001\u001F", "\uFFFE", "a\\u0000b", "\0next1")) {
            assertEquals(payload, StringUtil.unescapeLirStringLiteral(StringUtil.escapeLirStringLiteral(payload)));
        }
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
                () -> assertEquals("name", StringUtil.decodeGdStringLexeme("&'name'")),
                () -> assertEquals("a\"b", StringUtil.decodeGdStringLexeme("&\"a\\\"b\"")),
                () -> assertEquals("a'b", StringUtil.decodeGdStringLexeme("&'a\\'b'")),
                () -> assertEquals("b", StringUtil.decodeGdStringLexeme("&\"\"\"b\"\"\"")),
                () -> assertEquals("b", StringUtil.decodeGdStringLexeme("&'''b'''")),
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
                () -> assertEquals("a\\\r", StringUtil.decodeGdStringLexeme("r'a\\\r'")),
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
                () -> assertMalformedLexeme("r'a\\\\' + 'b'"),
                () -> assertMalformedLexeme("r'''a''' + '''b'''"),
                () -> assertMalformedLexeme("plain")
        );
    }

    @Test
    public void decodeNodePathLexemeNormalizesPayload() {
        assertAll(
                () -> assertEquals("a/b", StringUtil.decodeNodePathLexeme("^\"a/b\"")),
                () -> assertEquals("a\"b", StringUtil.decodeNodePathLexeme("^\"a\\\"b\"")),
                () -> assertEquals("Node/Child", StringUtil.decodeNodePathLexeme("^'Node/Child'")),
                () -> assertEquals("a'b", StringUtil.decodeNodePathLexeme("^'a\\'b'")),
                () -> assertEquals("a/b", StringUtil.decodeNodePathLexeme("^\"\"\"a/b\"\"\"")),
                () -> assertEquals("a/b", StringUtil.decodeNodePathLexeme("^'''a/b'''")),
                () -> assertEquals("A", StringUtil.decodeNodePathLexeme("^\"\\u0041\"")),
                () -> assertEquals("", StringUtil.decodeNodePathLexeme("^\"\""))
        );
    }

    @Test
    public void decodeQuotedLexemesKeepsCrAndCrlfPayloadsDistinct() {
        for (var prefix : List.of("", "&", "^")) {
            assertEquals("a\\'b", decodeQuotedLexeme(prefix, "'a\\\r'b'"), prefix);
            assertEquals("ab", decodeQuotedLexeme(prefix, "'a\\\r\nb'"), prefix);
            assertEquals("a\\'''b", decodeQuotedLexeme(prefix, "'''a\\\r'''b'''"), prefix);
            assertEquals("a'''b", decodeQuotedLexeme(prefix, "'''a\\'''b'''"), prefix);
            assertEquals("a", decodeQuotedLexeme(prefix, "'''a\\\r\n'''"), prefix);
        }
    }

    @Test
    public void decodeQuotedLexemesRequiresFirstUnescapedDelimiterAtEnd() {
        for (var prefix : List.of("", "&", "^")) {
            assertMalformedQuotedLexeme(prefix, "'a\\\r");
            assertMalformedQuotedLexeme(prefix, "'a\\\r'");
            assertMalformedQuotedLexeme(prefix, "'''a\\\r'''");
            assertMalformedQuotedLexeme(prefix, "'a\\'");
            assertMalformedQuotedLexeme(prefix, "'a' + 'b'");
            assertMalformedQuotedLexeme(prefix, "'''a''' + '''b'''");
        }
    }

    @Test
    public void decodeQuotedLexemesKeepsPayloadEscapeErrorsDistinct() {
        for (var prefix : List.of("", "&", "^")) {
            var error = assertThrows(IllegalArgumentException.class, () -> decodeQuotedLexeme(prefix, "'\\q'"));
            assertEquals("Invalid escape in literal: \\q", error.getMessage(), prefix);
        }
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

    @Test
    public void parseGdIntegerLexemeParsesAllRadixesAndSeparators() {
        assertAll(
                () -> assertEquals(42L, StringUtil.parseGdIntegerLexeme("42")),
                () -> assertEquals(0L, StringUtil.parseGdIntegerLexeme("0")),
                () -> assertEquals(31L, StringUtil.parseGdIntegerLexeme("0x1F")),
                () -> assertEquals(31L, StringUtil.parseGdIntegerLexeme("0X1f")),
                () -> assertEquals(10L, StringUtil.parseGdIntegerLexeme("0b1010")),
                () -> assertEquals(15L, StringUtil.parseGdIntegerLexeme("0o17")),
                () -> assertEquals(15L, StringUtil.parseGdIntegerLexeme("0O1_7")),
                () -> assertEquals(1_000_000L, StringUtil.parseGdIntegerLexeme("1_000_000")),
                () -> assertEquals(0xFFFFL, StringUtil.parseGdIntegerLexeme("0xFF_FF")),
                () -> assertEquals(42L, StringUtil.parseGdIntegerLexeme("  42  ")),
                () -> assertEquals(Long.MAX_VALUE, StringUtil.parseGdIntegerLexeme("9223372036854775807"))
        );
    }

    @Test
    public void parseGdIntegerLexemesRejectSignsAndNonAsciiOrOutOfRadixDigits() {
        for (var lexeme : List.of(
                "0x+1", "0x-1", "0b+1_0", "0O-1_7",
                "1_١", "0b1_０", "0o1_٧", "0xA_Ｆ",
                "0b10_2", "0o1_8", "0xA_G"
        )) {
            assertNull(StringUtil.parseGdIntegerLexeme(lexeme), lexeme);
            assertNull(StringUtil.parseGdUnsignedIntegerLexeme(lexeme), lexeme);
        }
    }

    @Test
    public void parseGdIntegerLexemeRejectsMalformedSignedAndOverflowingLexemes() {
        assertAll(
                // The sign is owned by the AST unary expression, not by this lexeme helper.
                () -> assertNull(StringUtil.parseGdIntegerLexeme("-5")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("+5")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("   ")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("0x")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("12a")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("0xG")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("1.5")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("9223372036854775808")),
                // Hex bit patterns above the signed 64-bit range count as overflow, not wrap.
                () -> assertNull(StringUtil.parseGdIntegerLexeme("0xFFFFFFFFFFFFFFFF")),
                // Underscores are legal only between two digits of the active radix.
                () -> assertNull(StringUtil.parseGdIntegerLexeme("1__0")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("_1")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("1_")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("0x_FF")),
                () -> assertNull(StringUtil.parseGdIntegerLexeme("0xF_")),
                () -> assertThrows(NullPointerException.class, () -> StringUtil.parseGdIntegerLexeme(null))
        );
    }

    @Test
    public void isGdLongMinMagnitudeLexemeAcceptsExactlyTwoToThePowerOf63InAllRadixes() {
        for (var lexeme : List.of(
                "9223372036854775808", "9_223_372_036_854_775_808",
                "0x8000000000000000", "0X8000_0000_0000_0000",
                "0o1" + "0".repeat(21), "0O1" + "_000".repeat(7),
                "0b1" + "0".repeat(63), "0B1000" + "_0000".repeat(15)
        )) {
            assertTrue(StringUtil.isGdLongMinMagnitudeLexeme(lexeme), lexeme);
        }
        for (var lexeme : List.of(
                "9223372036854775807", "9223372036854775809",
                "0x7FFFFFFFFFFFFFFF", "0x8000000000000001", "0xFFFFFFFFFFFFFFFF",
                "18446744073709551616", "99999999999999999999999999",
                "-9223372036854775808", "+9223372036854775808",
                "0x+8000000000000000", "0x+8_000000000000000", "0x-8000000000000000",
                "٩_223372036854775808", "0x８000000000000000",
                "_9223372036854775808", "9223372036854775808_", "9__223_372_036_854_775_808",
                "0x_8000000000000000", "0b1" + "0".repeat(62) + "2",
                "9223372036854775808.0", "9223372036854775808e0"
        )) {
            assertFalse(StringUtil.isGdLongMinMagnitudeLexeme(lexeme), lexeme);
        }
    }

    @Test
    public void isGdLongMinUnaryRequiresDirectIntegerMagnitudeUnderMinus() {
        var magnitude = new LiteralExpression("integer", "9223372036854775808", TINY);
        var compatibleNumber = new LiteralExpression("number", "0x8000000000000000", TINY);
        var negation = new UnaryExpression("-", magnitude, TINY);
        assertAll(
                () -> assertTrue(StringUtil.isGdLongMinUnary(negation)),
                () -> assertTrue(StringUtil.isGdLongMinUnary(new UnaryExpression("-", compatibleNumber, TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression("+", magnitude, TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression("~", magnitude, TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression("-", negation, TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression(
                        "-", new LiteralExpression("float", magnitude.sourceText(), TINY), TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression(
                        "-", new LiteralExpression("number", "9223372036854775808.0", TINY), TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression(
                        "-", new LiteralExpression("integer", "9223372036854775809", TINY), TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression(
                        "-", new LiteralExpression("integer", "0x+8000000000000000", TINY), TINY))),
                () -> assertFalse(StringUtil.isGdLongMinUnary(new UnaryExpression(
                        "-", new LiteralExpression("number", "٩_223372036854775808", TINY), TINY)))
        );
    }

    @Test
    public void parseGdFloatLexemeParsesDecimalFractionAndExponentForms() {
        assertAll(
                () -> assertEquals(1.5, StringUtil.parseGdFloatLexeme("1.5")),
                () -> assertEquals(0.5, StringUtil.parseGdFloatLexeme(".5")),
                () -> assertEquals(1.0, StringUtil.parseGdFloatLexeme("1.")),
                () -> assertEquals(1.0e10, StringUtil.parseGdFloatLexeme("1e10")),
                () -> assertEquals(1.5e-2, StringUtil.parseGdFloatLexeme("1.5e-2")),
                () -> assertEquals(1.5e2, StringUtil.parseGdFloatLexeme("1.5E+2")),
                () -> assertEquals(1_000.5, StringUtil.parseGdFloatLexeme("1_000.5")),
                () -> assertEquals(1.5, StringUtil.parseGdFloatLexeme("  1.5  "))
        );
    }

    @Test
    public void parseGdFloatLexemeRejectsMalformedSignedAndUnderscoreLexemes() {
        assertAll(
                () -> assertNull(StringUtil.parseGdFloatLexeme("-1.5")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("+1.5")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("   ")),
                () -> assertNull(StringUtil.parseGdFloatLexeme(".")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("e10")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("1e")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("1.5.2")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("1__0.5")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("_1.5")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("1.5_")),
                () -> assertNull(StringUtil.parseGdFloatLexeme("1._5")),
                () -> assertThrows(NullPointerException.class, () -> StringUtil.parseGdFloatLexeme(null))
        );
    }

    private static String decodeQuotedLexeme(String prefix, String quoted) {
        var lexeme = prefix + quoted;
        return "^".equals(prefix) ? StringUtil.decodeNodePathLexeme(lexeme) : StringUtil.decodeGdStringLexeme(lexeme);
    }

    private static void assertMalformedQuotedLexeme(String prefix, String quoted) {
        var lexeme = prefix + quoted;
        if ("^".equals(prefix)) {
            assertMalformedNodePathLexeme(lexeme);
        } else {
            assertMalformedLexeme(lexeme);
        }
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
