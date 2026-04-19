package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// Stable generated-symbol identity for one exact-engine helper surface.
/// Hash-only metadata drift must not change this key.
public record EngineMethodSymbolKey(
        @NotNull String ownerClassName,
        @NotNull String methodName,
        boolean isStatic,
        @NotNull EngineMethodAbiSignature abiSignature
) {
    private static final @NotNull Pattern NON_C_IDENTIFIER_PATTERN = Pattern.compile("[^A-Za-z0-9_]+");

    public EngineMethodSymbolKey {
        Objects.requireNonNull(ownerClassName);
        Objects.requireNonNull(methodName);
        Objects.requireNonNull(abiSignature);
    }

    public static @Nullable EngineMethodSymbolKey from(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        if (resolved.mode() != BackendMethodCallResolver.DispatchMode.ENGINE) {
            return null;
        }
        if (resolved.engineMethodBindSpec() == null) {
            return null;
        }
        return new EngineMethodSymbolKey(
                resolved.ownerClassName(),
                resolved.methodName(),
                resolved.isStatic(),
                EngineMethodAbiSignature.from(resolved)
        );
    }

    public @NotNull String symbolId() {
        return abiSignature.descriptor();
    }

    public @NotNull String renderBindAccessorName() {
        var name = new StringBuilder("gdcc_engine_method_bind_");
        if (isStatic) {
            name.append("static_");
        }
        return appendQualifiedStem(name).toString();
    }

    public @NotNull String renderCallHelperName() {
        var name = new StringBuilder(abiSignature.vararg() ? "gdcc_engine_callv_" : "gdcc_engine_call_");
        if (isStatic) {
            name.append("static_");
        }
        return appendQualifiedStem(name).toString();
    }

    private @NotNull StringBuilder appendQualifiedStem(@NotNull StringBuilder name) {
        return name.append(sanitizeCIdentifierFragment(ownerClassName))
                .append("_")
                .append(sanitizeCIdentifierFragment(methodName))
                .append("_")
                .append(symbolId());
    }

    private static @NotNull String sanitizeCIdentifierFragment(@NotNull String raw) {
        var normalized = raw.toLowerCase(Locale.ROOT);
        var sanitized = NON_C_IDENTIFIER_PATTERN.matcher(normalized).replaceAll("_").replaceAll("_+", "_");
        if (sanitized.startsWith("_")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.endsWith("_")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("Identifier fragment becomes empty after sanitization: " + raw);
        }
        return sanitized;
    }
}
