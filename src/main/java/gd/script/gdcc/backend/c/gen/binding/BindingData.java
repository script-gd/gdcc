package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared bound-method surface descriptor used by template binding generation.
/// Instance wrappers carry owner class identity so self fat materialization is owner-specific.
public record BindingData(
        @Nullable String ownerClassName,
        @NotNull List<GdType> paramTypes,
        @NotNull GdType returnType,
        @NotNull List<GdType> defaultVariables,
        boolean staticMethod
) {
    public BindingData {
        paramTypes = List.copyOf(paramTypes);
        defaultVariables = List.copyOf(defaultVariables);
        if (!staticMethod) {
            Objects.requireNonNull(ownerClassName, "instance BindingData requires ownerClassName");
            if (ownerClassName.isBlank()) {
                throw new IllegalArgumentException("instance BindingData ownerClassName must not be blank");
            }
        }
    }

    public boolean isInstanceMethod() {
        return !staticMethod;
    }
}
