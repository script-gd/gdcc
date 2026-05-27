package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.EngineMethodSymbolKey;
import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/// Module-scope collector that keeps first-hit render order stable across successful body renders.
final class EngineMethodUsageSession
        extends AbstractUsageSession<
        EngineMethodSymbolKey,
        BackendMethodCallResolver.ResolvedMethodCall,
        EngineMethodUsageBuffer> {
    @Override
    @NotNull EngineMethodUsageBuffer newFunctionBuffer() {
        return EngineMethodUsageBuffer.create();
    }

    @NotNull List<BackendMethodCallResolver.ResolvedMethodCall> snapshot() {
        return snapshotValues();
    }
}
