package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.EngineMethodSymbolKey;
import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import org.jetbrains.annotations.NotNull;

/// Function-scope buffer for exact engine-method usage candidates.
/// Entries stay local until the enclosing body render succeeds and the session commits them.
final class EngineMethodUsageBuffer
        extends AbstractUsageBuffer<EngineMethodSymbolKey, BackendMethodCallResolver.ResolvedMethodCall> {
    private static final @NotNull EngineMethodUsageBuffer NO_OP = new EngineMethodUsageBuffer(true);

    private EngineMethodUsageBuffer() {
        this(false);
    }

    private EngineMethodUsageBuffer(boolean noOp) {
        super(noOp);
    }

    static @NotNull EngineMethodUsageBuffer noOp() {
        return NO_OP;
    }

    void record(@NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        if (isNoOp()) {
            return;
        }
        var key = EngineMethodSymbolKey.from(resolved);
        if (key == null) {
            return;
        }
        putIfAbsent(key, resolved);
    }

    static @NotNull EngineMethodUsageBuffer create() {
        return new EngineMethodUsageBuffer();
    }
}
