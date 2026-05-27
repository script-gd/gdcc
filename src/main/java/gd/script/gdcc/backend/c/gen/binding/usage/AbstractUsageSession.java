package gd.script.gdcc.backend.c.gen.binding.usage;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class AbstractUsageSession<Key, Value, Buffer extends AbstractUsageBuffer<Key, Value>> {
    private final LinkedHashMap<Key, Value> entriesByKey = new LinkedHashMap<>();

    abstract @NotNull Buffer newFunctionBuffer();

    public void commit(@NotNull Buffer buffer) {
        for (var entry : buffer.snapshotMap().entrySet()) {
            putFromBuffer(entry.getKey(), entry.getValue());
        }
    }

    protected void putFromBuffer(@NotNull Key key, @NotNull Value value) {
        entriesByKey.putIfAbsent(key, value);
    }

    protected final @NotNull Value putEntry(@NotNull Key key, @NotNull Value value) {
        entriesByKey.put(key, value);
        return value;
    }

    protected final Value getEntry(@NotNull Key key) {
        return entriesByKey.get(key);
    }

    protected final @NotNull Map<Key, Value> snapshotMap() {
        return new LinkedHashMap<>(entriesByKey);
    }

    protected final @NotNull List<Value> snapshotValues() {
        return List.copyOf(entriesByKey.values());
    }
}
