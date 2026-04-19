package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Stable callable surface for one exact-engine helper.
/// Bind lookup hashes intentionally live elsewhere and never participate in this signature.
public record EngineMethodAbiSignature(
        @NotNull List<GdType> parameterTypes,
        @NotNull GdType returnType,
        boolean vararg
) {
    public EngineMethodAbiSignature {
        Objects.requireNonNull(parameterTypes);
        Objects.requireNonNull(returnType);
        parameterTypes = List.copyOf(parameterTypes);
        for (var parameterType : parameterTypes) {
            Objects.requireNonNull(parameterType);
            if (parameterType instanceof GdVoidType) {
                throw new IllegalArgumentException("Void is not a valid engine helper parameter type");
            }
        }
    }

    public static @NotNull EngineMethodAbiSignature from(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return new EngineMethodAbiSignature(
                resolved.parameters().stream().map(BackendMethodCallResolver.MethodParamSpec::type).toList(),
                resolved.returnType(),
                resolved.isVararg()
        );
    }

    public @NotNull String descriptor() {
        return EngineMethodAbiCodec.generate(this);
    }
}
