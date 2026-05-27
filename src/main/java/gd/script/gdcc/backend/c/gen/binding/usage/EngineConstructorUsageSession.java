package gd.script.gdcc.backend.c.gen.binding.usage;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/// Module-scope collector for constructor wrappers emitted into `engine_method_binds.h`.
final class EngineConstructorUsageSession
        extends AbstractUsageSession<String, EngineConstructorUsage, EngineConstructorUsageBuffer> {
    @Override
    @NotNull EngineConstructorUsageBuffer newFunctionBuffer() {
        return EngineConstructorUsageBuffer.create();
    }

    @NotNull List<EngineConstructorUsage> snapshot() {
        return snapshotValues();
    }
}
