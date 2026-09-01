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
    /// Accepted inputs are ordinary string literals like `"text"` and `StringName` literals like
    /// `&"text"`. The returned payload never contains the outer quoting syntax.
    public static @NotNull String decodeGdStringLexeme(@NotNull String lexeme) {
        var text = Objects.requireNonNull(lexeme, "lexeme must not be null");
        if (text.startsWith("&\"")) {
            return decodeQuotedLexeme(text, 2);
        }
        if (text.startsWith("\"")) {
            return decodeQuotedLexeme(text, 1);
        }
        throw invalidGdStringLexeme(text);
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

    public static @NotNull String unescapeQuoted(@NotNull String content) {
        var out = new StringBuilder();
        for (var i = 0; i < content.length(); i++) {
            var ch = content.charAt(i);
            if (ch != '\\') {
                out.append(ch);
                continue;
            }
            if (i + 1 >= content.length()) {
                out.append('\\');
                break;
            }
            var next = content.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case 'u' -> {
                    if (i + 4 >= content.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape in literal: \\" + next);
                    }
                    var hex = content.substring(i + 1, i + 5);
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                case 'U' -> {
                    if (i + 8 >= content.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape in literal: \\" + next);
                    }
                    var hex = content.substring(i + 1, i + 9);
                    out.appendCodePoint(Integer.parseInt(hex, 16));
                    i += 8;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static @NotNull String decodeQuotedLexeme(@NotNull String lexeme, int contentStartIndex) {
        if (lexeme.length() <= contentStartIndex || lexeme.charAt(lexeme.length() - 1) != '"') {
            throw invalidGdStringLexeme(lexeme);
        }
        return unescapeQuoted(lexeme.substring(contentStartIndex, lexeme.length() - 1));
    }

    private static @NotNull IllegalArgumentException invalidGdStringLexeme(@NotNull String lexeme) {
        return new IllegalArgumentException("Invalid GDScript string lexeme: " + lexeme);
    }
}
