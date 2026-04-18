package dev.superice.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared outward binding metadata surface for method/property registration.
public record BoundMetadata(
        @NotNull String typeEnumLiteral,
        @NotNull String hintEnumLiteral,
        @NotNull String hintStringExpr,
        @NotNull String classNameExpr,
        @NotNull String usageExpr
) {
    public BoundMetadata {
        Objects.requireNonNull(typeEnumLiteral);
        Objects.requireNonNull(hintEnumLiteral);
        Objects.requireNonNull(hintStringExpr);
        Objects.requireNonNull(classNameExpr);
        Objects.requireNonNull(usageExpr);
    }
}
