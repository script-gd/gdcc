package dev.superice.gdcc.scope;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Descriptor for a global utility function, including detailed parameter information.
public record FunctionSignature(
        String name,
        List<Parameter> parameters,
        boolean isVararg,
        @Nullable GdType returnType
) {
    public record Parameter(String name, @Nullable GdType type, @Nullable String defaultValue) { }

    public int parameterCount() { return parameters == null ? 0 : parameters.size(); }
}
