package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;

public final class StringUtil {
    private StringUtil() {
    }

    public static @NotNull String escapeStringLiteral(@NotNull String str) {
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
