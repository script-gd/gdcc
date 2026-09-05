package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StringUtil {
    private StringUtil() {
    }

    public static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    public static @Nullable String requireNullableNonBlank(@Nullable String value, @NotNull String fieldName) {
        return value == null ? null : requireNonBlank(value, fieldName);
    }

    public static @NotNull String requireTrimmedNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    public static @NotNull String trimToEmpty(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    public static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static @NotNull String normalizeIndentedSnippet(@NotNull String rawSnippet) {
        var normalized = rawSnippet.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) {
            return rawSnippet.trim();
        }
        var lines = normalized.lines().toList();
        var commonIndent = Integer.MAX_VALUE;
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                indent++;
            }
            commonIndent = Math.min(commonIndent, indent);
        }
        if (commonIndent == Integer.MAX_VALUE || commonIndent == 0) {
            return normalized;
        }
        var strippedLines = new ArrayList<String>(lines.size());
        for (var line : lines) {
            strippedLines.add(line.isBlank() ? "" : line.substring(Math.min(commonIndent, line.length())));
        }
        return String.join("\n", strippedLines);
    }

    public static @NotNull List<String> splitLines(@NotNull String text) {
        return text.lines().toList();
    }

    public static @NotNull String escapeStringLiteral(@NotNull String value) {
        var sb = new StringBuilder();
        for (var i = 0; i < value.length(); ) {
            var codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            switch (codePoint) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (codePoint >= 0x20 && codePoint <= 0x7E) {
                        sb.append((char) codePoint);
                    } else if (codePoint <= 0xFFFF) {
                        sb.append("\\u").append(String.format("%04X", codePoint));
                    } else {
                        sb.append("\\U").append(String.format("%08X", codePoint));
                    }
                }
            }
        }
        return sb.toString();
    }

    /// Decodes a full GDScript source lexeme into the runtime payload stored by LIR.
    ///
    /// Covers the complete Godot string literal form set: `"..."` / `'...'`, triple-quoted
    /// multiline `"""..."""` / `'''...'''`, their raw `r`-prefixed variants, and `&"..."`
    /// StringName literals. The returned payload never contains the outer quoting syntax.
    public static @NotNull String decodeGdStringLexeme(@NotNull String lexeme) {
        var text = Objects.requireNonNull(lexeme, "lexeme must not be null");
        var index = 0;
        var raw = false;
        if (index < text.length() && text.charAt(index) == 'r') {
            raw = true;
            index++;
        } else if (index < text.length() && text.charAt(index) == '&') {
            // StringName literals exist only in the plain double-quoted form.
            if (index + 1 >= text.length() || text.charAt(index + 1) != '"') {
                throw invalidGdStringLexeme(text);
            }
            return decodeStringBody(text, index + 1, '"', false);
        }
        if (index >= text.length() || (text.charAt(index) != '"' && text.charAt(index) != '\'')) {
            throw invalidGdStringLexeme(text);
        }
        return decodeStringBody(text, index, text.charAt(index), raw);
    }

    /// Extracts the payload of a string lexeme whose opening quote sits at `quoteIndex`, handling
    /// both the single-quote and triple-quote (multiline) delimiter forms.
    private static @NotNull String decodeStringBody(@NotNull String text, int quoteIndex, char quote, boolean raw) {
        var contentStart = quoteIndex + 1;
        if (contentStart + 1 < text.length()
                && text.charAt(contentStart) == quote
                && text.charAt(contentStart + 1) == quote) {
            contentStart += 2;
            var closingDelimiter = String.valueOf(new char[]{quote, quote, quote});
            if (text.length() < contentStart + 3 || !text.endsWith(closingDelimiter)) {
                throw invalidGdStringLexeme(text);
            }
            return decodeStringContent(text.substring(contentStart, text.length() - 3), raw);
        }
        if (text.length() < contentStart + 1 || text.charAt(text.length() - 1) != quote) {
            throw invalidGdStringLexeme(text);
        }
        return decodeStringContent(text.substring(contentStart, text.length() - 1), raw);
    }

    /// Raw string payloads are verbatim: the Godot tokenizer only treats `\<quote>` and `\\`
    /// specially for termination, and both pairs stay in the payload unchanged.
    private static @NotNull String decodeStringContent(@NotNull String content, boolean raw) {
        return raw ? content : unescapeQuoted(content);
    }

    /// Decodes a `^"..."` NodePath source lexeme into the runtime payload stored by LIR.
    ///
    /// The returned payload never contains the leading `^` or the outer quotes. Malformed lexemes
    /// fail fast with `IllegalArgumentException`; producers that treat unreducible shapes as
    /// non-constant must catch and convert to their own miss sentinel.
    public static @NotNull String decodeNodePathLexeme(@NotNull String lexeme) {
        var text = Objects.requireNonNull(lexeme, "lexeme must not be null");
        if (!text.startsWith("^\"") || text.length() < 3 || text.charAt(text.length() - 1) != '"') {
            throw new IllegalArgumentException("Invalid GDScript node path lexeme: " + lexeme);
        }
        return unescapeQuoted(text.substring(2, text.length() - 1));
    }

    /// Decodes a `$...` / `%...` get-node source lexeme into the NodePath payload passed to the
    /// runtime `Node.get_node` call.
    ///
    /// Quoted forms (`$"..."` / `%"..."`) decode the quoted section; bare forms keep the path text
    /// as-is (including `/root/...` absolute paths). The `%` prefix is preserved so the runtime
    /// unique-name lookup stays distinguishable from a plain path.
    public static @NotNull String decodeGetNodePathLexeme(@NotNull String lexeme) {
        var text = Objects.requireNonNull(lexeme, "lexeme must not be null");
        if (text.length() < 2 || (text.charAt(0) != '$' && text.charAt(0) != '%')) {
            throw new IllegalArgumentException("Invalid GDScript get-node lexeme: " + lexeme);
        }
        if (text.charAt(1) != '"') {
            return text.charAt(0) == '$' ? text.substring(1) : text;
        }
        if (text.length() < 3 || text.charAt(text.length() - 1) != '"') {
            throw new IllegalArgumentException("Invalid GDScript get-node lexeme: " + lexeme);
        }
        var decoded = unescapeQuoted(text.substring(2, text.length() - 1));
        return text.charAt(0) == '%' ? "%" + decoded : decoded;
    }

    /// Decodes the escape sequences of a regular (non-raw) GDScript string payload.
    ///
    /// The accepted escape set matches the Godot 4.5.1 tokenizer: `a b f n r t v ' "` and `\`
    /// after a backslash, backslash-`u` with exactly 4 hex digits, backslash-`U` with exactly 6
    /// hex digits, and backslash-newline line continuation (contributes nothing). Escaped UTF-16
    /// lead surrogates must pair with a trail surrogate escape, combining into one code point.
    /// Unknown escapes, invalid hex digits, unpaired surrogates, and code points above U+10FFFF
    /// are all hard errors — Godot reports them instead of silently dropping the backslash.
    public static @NotNull String unescapeQuoted(@NotNull String content) {
        var out = new StringBuilder();
        var pendingLeadSurrogate = -1;
        for (var i = 0; i < content.length(); i++) {
            var ch = content.charAt(i);
            if (ch != '\\') {
                pendingLeadSurrogate = requireNoPendingLeadSurrogate(pendingLeadSurrogate);
                out.append(ch);
                continue;
            }
            if (i + 1 >= content.length()) {
                throw new IllegalArgumentException("Unterminated escape sequence in literal: lone trailing backslash");
            }
            var next = content.charAt(++i);
            switch (next) {
                case 'a' -> pendingLeadSurrogate = appendEscaped(out, '\u0007', pendingLeadSurrogate);
                case 'b' -> pendingLeadSurrogate = appendEscaped(out, '\b', pendingLeadSurrogate);
                case 'f' -> pendingLeadSurrogate = appendEscaped(out, '\f', pendingLeadSurrogate);
                case 'n' -> pendingLeadSurrogate = appendEscaped(out, '\n', pendingLeadSurrogate);
                case 'r' -> pendingLeadSurrogate = appendEscaped(out, '\r', pendingLeadSurrogate);
                case 't' -> pendingLeadSurrogate = appendEscaped(out, '\t', pendingLeadSurrogate);
                case 'v' -> pendingLeadSurrogate = appendEscaped(out, '\u000B', pendingLeadSurrogate);
                case '\'' -> pendingLeadSurrogate = appendEscaped(out, '\'', pendingLeadSurrogate);
                case '"' -> pendingLeadSurrogate = appendEscaped(out, '"', pendingLeadSurrogate);
                case '\\' -> pendingLeadSurrogate = appendEscaped(out, '\\', pendingLeadSurrogate);
                case 'u', 'U' -> {
                    var hexLength = next == 'u' ? 4 : 6;
                    if (i + hexLength >= content.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape in literal: \\" + next);
                    }
                    var hex = content.substring(i + 1, i + 1 + hexLength);
                    int value;
                    try {
                        value = Integer.parseInt(hex, 16);
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException(
                                "Invalid hexadecimal digit in unicode escape sequence: \\" + next + hex,
                                exception
                        );
                    }
                    i += hexLength;
                    pendingLeadSurrogate = appendUnicodeEscape(out, value, pendingLeadSurrogate);
                }
                case '\n' -> // Backslash-newline line continuation contributes nothing to the payload.
                        pendingLeadSurrogate = requireNoPendingLeadSurrogate(pendingLeadSurrogate);
                case '\r' -> {
                    pendingLeadSurrogate = requireNoPendingLeadSurrogate(pendingLeadSurrogate);
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                        // Backslash-CRLF is a line continuation and contributes nothing.
                        i++;
                    } else {
                        // Godot keeps the backslash and drops the lone carriage return.
                        out.append('\\');
                    }
                }
                default -> throw new IllegalArgumentException("Invalid escape in literal: \\" + next);
            }
        }
        requireNoPendingLeadSurrogate(pendingLeadSurrogate);
        return out.toString();
    }

    /// Appends a simple ASCII escape value. A pending lead surrogate makes the sequence invalid
    /// because the surrogate never found its trail pair.
    private static int appendEscaped(@NotNull StringBuilder out, char value, int pendingLeadSurrogate) {
        requireNoPendingLeadSurrogate(pendingLeadSurrogate);
        out.append(value);
        return -1;
    }

    /// Appends a unicode escape value, combining UTF-16 lead/trail surrogate pairs into a
    /// single code point exactly like the Godot tokenizer does.
    private static int appendUnicodeEscape(@NotNull StringBuilder out, int value, int pendingLeadSurrogate) {
        if (value >= 0xD800 && value <= 0xDBFF) {
            if (pendingLeadSurrogate >= 0) {
                throw new IllegalArgumentException("Invalid UTF-16 sequence in literal: unpaired lead surrogate");
            }
            return value;
        }
        if (value >= 0xDC00 && value <= 0xDFFF) {
            if (pendingLeadSurrogate < 0) {
                throw new IllegalArgumentException("Invalid UTF-16 sequence in literal: unpaired trail surrogate");
            }
            out.appendCodePoint((pendingLeadSurrogate << 10) + value - ((0xD800 << 10) + 0xDC00 - 0x10000));
            return -1;
        }
        requireNoPendingLeadSurrogate(pendingLeadSurrogate);
        if (value > 0x10FFFF) {
            throw new IllegalArgumentException("Invalid unicode escape in literal: code point above U+10FFFF");
        }
        out.appendCodePoint(value);
        return -1;
    }

    private static int requireNoPendingLeadSurrogate(int pendingLeadSurrogate) {
        if (pendingLeadSurrogate >= 0) {
            throw new IllegalArgumentException("Invalid UTF-16 sequence in literal: unpaired lead surrogate");
        }
        return -1;
    }

    private static @NotNull IllegalArgumentException invalidGdStringLexeme(@NotNull String lexeme) {
        return new IllegalArgumentException("Invalid GDScript string lexeme: " + lexeme);
    }
}
