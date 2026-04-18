package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/// Shared bound-method surface descriptor used by template binding generation.
public record BindingData(
        @NotNull List<GdType> paramTypes,
        @NotNull GdType returnType,
        @NotNull List<GdType> defaultVariables,
        boolean staticMethod
) {
    public BindingData {
        paramTypes = List.copyOf(paramTypes);
        defaultVariables = List.copyOf(defaultVariables);
    }
}
