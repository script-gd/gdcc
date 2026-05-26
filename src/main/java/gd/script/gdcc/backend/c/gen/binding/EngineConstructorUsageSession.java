package gd.script.gdcc.backend.c.gen.binding;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;

/// Module-scope collector for constructor wrappers emitted into `engine_method_binds.h`.
public final class EngineConstructorUsageSession {
    private final LinkedHashMap<String, EngineConstructorUsage> constructorsByClassName = new LinkedHashMap<>();

    public @NotNull EngineConstructorUsageBuffer newFunctionBuffer() {
        return EngineConstructorUsageBuffer.create();
    }

    public void commit(@NotNull EngineConstructorUsageBuffer buffer) {
        for (var entry : buffer.snapshot().entrySet()) {
            constructorsByClassName.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    public @NotNull List<EngineConstructorUsage> snapshot() {
        return List.copyOf(constructorsByClassName.values());
    }
}
