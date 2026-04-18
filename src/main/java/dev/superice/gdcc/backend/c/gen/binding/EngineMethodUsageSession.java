package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;

/// Module-scope collector that keeps first-hit render order stable across successful body renders.
public final class EngineMethodUsageSession {
    private final LinkedHashMap<EngineMethodBindKey, BackendMethodCallResolver.ResolvedMethodCall> methodsByKey =
            new LinkedHashMap<>();

    public @NotNull EngineMethodUsageBuffer newFunctionBuffer() {
        return EngineMethodUsageBuffer.create();
    }

    public void commit(@NotNull EngineMethodUsageBuffer buffer) {
        for (var entry : buffer.snapshot().entrySet()) {
            methodsByKey.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    public @NotNull List<BackendMethodCallResolver.ResolvedMethodCall> snapshot() {
        return List.copyOf(methodsByKey.values());
    }
}
