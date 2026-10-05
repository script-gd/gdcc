package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/// Identity-keyed map whose EVERY mutation channel closes permanently on `freeze()` (LSP
/// foundation plan §2.1: a published analysis generation must be physically unwritable, not
/// read-only by convention).
///
/// Composition over `IdentityHashMap` rather than inheritance: JDK map views and default methods
/// (`replaceAll`, `Map.Entry.setValue`, iterator `remove`, `keySet().remove`, ...) do not funnel
/// through overridable mutators, so every write-capable operation is delegated explicitly and the
/// collection views are wrapped to consult the freeze flag at mutation time. Views obtained BEFORE
/// the freeze therefore close as well; reads and read iteration stay open for concurrent snapshot
/// queries.
public final class FreezableIdentityMap<K, V> implements Map<K, V> {
    private final IdentityHashMap<K, V> values = new IdentityHashMap<>();
    private volatile boolean frozen;

    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    private void requireWritable() {
        if (frozen) {
            throw new IllegalStateException(
                    "This identity map belongs to a published snapshot generation and is frozen"
            );
        }
    }

    @Override
    public V put(K key, V value) {
        requireWritable();
        return values.put(key, value);
    }

    @Override
    public void putAll(@NotNull Map<? extends K, ? extends V> map) {
        requireWritable();
        values.putAll(map);
    }

    @Override
    public V remove(Object key) {
        requireWritable();
        return values.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        requireWritable();
        return values.remove(key, value);
    }

    @Override
    public void clear() {
        requireWritable();
        values.clear();
    }

    @Override
    public V putIfAbsent(K key, V value) {
        requireWritable();
        return values.putIfAbsent(key, value);
    }

    @Override
    public V replace(K key, V value) {
        requireWritable();
        return values.replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        requireWritable();
        return values.replace(key, oldValue, newValue);
    }

    @Override
    public void replaceAll(@NotNull BiFunction<? super K, ? super V, ? extends V> function) {
        requireWritable();
        values.replaceAll(function);
    }

    @Override
    public V computeIfAbsent(K key, @NotNull Function<? super K, ? extends V> mappingFunction) {
        requireWritable();
        return values.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(K key, @NotNull BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        requireWritable();
        return values.computeIfPresent(key, remappingFunction);
    }

    @Override
    public V compute(K key, @NotNull BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        requireWritable();
        return values.compute(key, remappingFunction);
    }

    @Override
    public V merge(K key, @NotNull V value, @NotNull BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        requireWritable();
        return values.merge(key, value, remappingFunction);
    }

    @Override
    public V get(Object key) {
        return values.get(key);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    @Override
    public boolean containsKey(Object key) {
        return values.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return values.containsValue(value);
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return values.equals(other);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }

    /// The views consult the freeze flag at mutation time, so a view obtained while the map was
    /// still writable also closes on `freeze()`.
    @Override
    public @NotNull Set<K> keySet() {
        return new GuardedKeySet(values.keySet());
    }

    @Override
    public @NotNull Collection<V> values() {
        return new GuardedValues(values.values());
    }

    @Override
    public @NotNull Set<Entry<K, V>> entrySet() {
        return new GuardedEntrySet(values.entrySet());
    }

    private final class GuardedKeySet extends AbstractSet<K> {
        private final Set<K> backing;

        private GuardedKeySet(@NotNull Set<K> backing) {
            this.backing = backing;
        }

        @Override
        public @NotNull Iterator<K> iterator() {
            var iterator = backing.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public K next() {
                    return iterator.next();
                }

                @Override
                public void remove() {
                    requireWritable();
                    iterator.remove();
                }
            };
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean contains(Object key) {
            return backing.contains(key);
        }

        @Override
        public boolean remove(Object key) {
            requireWritable();
            return backing.remove(key);
        }

        @Override
        public void clear() {
            requireWritable();
            backing.clear();
        }
    }

    private final class GuardedValues extends AbstractCollection<V> {
        private final Collection<V> backing;

        private GuardedValues(@NotNull Collection<V> backing) {
            this.backing = backing;
        }

        // IdentityHashMap compares values by reference; delegate both operations so the guarded
        // view keeps identity semantics consistent with `containsValue` instead of falling back
        // to `AbstractCollection`'s equals-based scan.
        @Override
        public boolean contains(Object value) {
            return backing.contains(value);
        }

        @Override
        public boolean remove(Object value) {
            requireWritable();
            return backing.remove(value);
        }

        @Override
        public @NotNull Iterator<V> iterator() {
            var iterator = backing.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public V next() {
                    return iterator.next();
                }

                @Override
                public void remove() {
                    requireWritable();
                    iterator.remove();
                }
            };
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public void clear() {
            requireWritable();
            backing.clear();
        }
    }

    private final class GuardedEntrySet extends AbstractSet<Entry<K, V>> {
        private final Set<Entry<K, V>> backing;

        private GuardedEntrySet(@NotNull Set<Entry<K, V>> backing) {
            this.backing = backing;
        }

        @Override
        public @NotNull Iterator<Entry<K, V>> iterator() {
            var iterator = backing.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Entry<K, V> next() {
                    return new GuardedEntry(iterator.next());
                }

                @Override
                public void remove() {
                    requireWritable();
                    iterator.remove();
                }
            };
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean contains(Object entry) {
            return backing.contains(entry);
        }

        @Override
        public boolean remove(Object entry) {
            requireWritable();
            return backing.remove(entry);
        }

        @Override
        public void clear() {
            requireWritable();
            backing.clear();
        }
    }

    /// Entry whose `setValue` consults the freeze flag; reads delegate to the live entry.
    private final class GuardedEntry implements Entry<K, V> {
        private final Entry<K, V> backing;

        private GuardedEntry(@NotNull Entry<K, V> backing) {
            this.backing = backing;
        }

        @Override
        public K getKey() {
            return backing.getKey();
        }

        @Override
        public V getValue() {
            return backing.getValue();
        }

        @Override
        public V setValue(V value) {
            requireWritable();
            return backing.setValue(value);
        }

        @Override
        public boolean equals(Object other) {
            return backing.equals(other);
        }

        @Override
        public int hashCode() {
            return backing.hashCode();
        }

        @Override
        public String toString() {
            return backing.toString();
        }
    }
}
