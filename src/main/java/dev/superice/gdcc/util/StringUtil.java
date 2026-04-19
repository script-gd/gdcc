package dev.superice.gdcc.util;

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
            out.append(switch (next) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '\\' -> '\\';
                case '"' -> '"';
                default -> next;
            });
        }
        return out.toString();
    }
}
