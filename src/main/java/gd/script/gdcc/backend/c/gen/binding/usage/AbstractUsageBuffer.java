package gd.script.gdcc.backend.c.gen.binding.usage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

abstract class AbstractUsageBuffer<Key, Value> {
    private final boolean noOp;
    private final LinkedHashMap<Key, Value> entriesByKey = new LinkedHashMap<>();

    protected AbstractUsageBuffer(boolean noOp) {
        this.noOp = noOp;
    }

    protected final boolean isNoOp() {
        return noOp;
    }

    protected final void putIfAbsent(@NotNull Key key, @NotNull Value value) {
        if (!noOp) {
            entriesByKey.putIfAbsent(key, value);
        }
    }

    protected final void put(@NotNull Key key, @NotNull Value value) {
        if (!noOp) {
            entriesByKey.put(key, value);
        }
    }

    protected final @Nullable Value get(@NotNull Key key) {
        return entriesByKey.get(key);
    }

    protected final boolean containsKey(@NotNull Key key) {
        return entriesByKey.containsKey(key);
    }

    final @NotNull Map<Key, Value> snapshotMap() {
        return new LinkedHashMap<>(entriesByKey);
    }
}
