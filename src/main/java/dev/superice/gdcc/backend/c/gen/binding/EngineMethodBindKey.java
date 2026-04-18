package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Exact engine helper ABI depends on bind identity plus static/vararg surface shape.
record EngineMethodBindKey(
        @NotNull String ownerClassName,
        @NotNull String methodName,
        long hash,
        boolean isStatic,
        boolean isVararg
) {
    EngineMethodBindKey {
        Objects.requireNonNull(ownerClassName);
        Objects.requireNonNull(methodName);
    }

    static @Nullable EngineMethodBindKey from(@NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        if (resolved.mode() != BackendMethodCallResolver.DispatchMode.ENGINE) {
            return null;
        }
        var bindSpec = resolved.engineMethodBindSpec();
        if (bindSpec == null) {
            return null;
        }
        return new EngineMethodBindKey(
                resolved.ownerClassName(),
                resolved.methodName(),
                bindSpec.hash(),
                resolved.isStatic(),
                resolved.isVararg()
        );
    }
}
