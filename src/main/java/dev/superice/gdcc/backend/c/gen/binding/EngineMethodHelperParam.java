package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Generated exact-engine helper parameter after backend surface normalization.
public record EngineMethodHelperParam(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull String cType,
        boolean pointerSurface,
        boolean bitfieldPassByRef
) {
    public EngineMethodHelperParam {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        Objects.requireNonNull(cType);
    }
}
