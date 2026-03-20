package dev.superice.gdcc.scope.resolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Shared helpers for the tiny structured type-text grammar supported by strict/compat declared-type parsing.
public final class ScopeTypeTextSupport {
    private ScopeTypeTextSupport() {
    }

    /// Any remaining bracket or comma token means this text is still a structured type expression, not a leaf.
    public static boolean looksStructuredTypeText(@NotNull String typeText) {
        return typeText.indexOf('[') >= 0
                || typeText.indexOf(']') >= 0
                || typeText.indexOf(',') >= 0;
    }

    /// GDScript rejects nested structured container declarations here, so splitting at the first comma is enough.
    public static @Nullable List<String> splitDictionaryTypeArgs(@NotNull String argsText) {
        var commaIndex = argsText.indexOf(',');
        if (commaIndex < 0) {
            return null;
        }
        var keyText = argsText.substring(0, commaIndex).trim();
        var valueText = argsText.substring(commaIndex + 1).trim();
        if (keyText.isEmpty() || valueText.isEmpty()) {
            return null;
        }
        return List.of(keyText, valueText);
    }
}
