package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/// Function-scope buffer for exact engine-method usage candidates.
/// Entries stay local until the enclosing body render succeeds and the session commits them.
public final class EngineMethodUsageBuffer {
    private static final @NotNull EngineMethodUsageBuffer NO_OP = new EngineMethodUsageBuffer(true);

    private final boolean noOp;
    private final LinkedHashMap<EngineMethodBindKey, BackendMethodCallResolver.ResolvedMethodCall> methodsByKey =
            new LinkedHashMap<>();

    private EngineMethodUsageBuffer() {
        this(false);
    }

    private EngineMethodUsageBuffer(boolean noOp) {
        this.noOp = noOp;
    }

    public static @NotNull EngineMethodUsageBuffer noOp() {
        return NO_OP;
    }

    public void record(@NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        if (noOp) {
            return;
        }
        var key = EngineMethodBindKey.from(resolved);
        if (key == null) {
            return;
        }
        methodsByKey.putIfAbsent(key, resolved);
    }

    @NotNull Map<EngineMethodBindKey, BackendMethodCallResolver.ResolvedMethodCall> snapshot() {
        return new LinkedHashMap<>(methodsByKey);
    }

    static @NotNull EngineMethodUsageBuffer create() {
        return new EngineMethodUsageBuffer();
    }
}
