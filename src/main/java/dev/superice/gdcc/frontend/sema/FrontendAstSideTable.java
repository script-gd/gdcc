package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/// Identity-based side table keyed by AST node identity instead of structural equality.
///
/// Since `gdparser 0.5.x`, every frontend AST node, including `SourceFile`, implements `Node`.
/// Frontend semantic side tables therefore key directly on `Node` and reject non-node objects
/// instead of pretending arbitrary helper objects are valid AST keys.
///
/// The table now also implements `Map` so semantic phases can use ordinary map-style helpers
/// such as `containsKey`, `entrySet`, `keySet`, and bulk `putAll` operations while still
/// preserving identity-key semantics through the underlying `IdentityHashMap`.
public final class FrontendAstSideTable<V> extends AbstractMap<Node, V> {
    private final IdentityHashMap<Node, V> values = new IdentityHashMap<>();

    @Override
    public @Nullable V put(@NotNull Node astNode, @NotNull V value) {
        return values.put(
                requireAstNode(astNode),
                requireValue(value)
        );
    }

    @Override
    public @Nullable V get(Object astNode) {
        return values.get(requireAstNodeKey(astNode));
    }

    public boolean contains(@NotNull Node astNode) {
        return containsKey(astNode);
    }

    @Override
    public boolean containsKey(Object astNode) {
        return values.containsKey(requireAstNodeKey(astNode));
    }

    @Override
    public boolean containsValue(Object value) {
        return values.containsValue(requireValue(value));
    }

    public void putAll(@NotNull FrontendAstSideTable<? extends V> other) {
        Objects.requireNonNull(other, "other must not be null");
        values.putAll(other.values);
    }

    @Override
    public void putAll(@NotNull Map<? extends Node, ? extends V> other) {
        Objects.requireNonNull(other, "other must not be null");
        other.forEach(this::put);
    }

    @Override
    public @Nullable V remove(Object astNode) {
        return values.remove(requireAstNodeKey(astNode));
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public void forEach(@NotNull BiConsumer<? super Node, ? super V> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        values.forEach(consumer);
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
    public @NotNull Set<Node> keySet() {
        return values.keySet();
    }

    @Override
    public @NotNull Collection<V> values() {
        return values.values();
    }

    @Override
    public @NotNull Set<Entry<Node, V>> entrySet() {
        return values.entrySet();
    }

    private static @NotNull Node requireAstNode(@Nullable Node astNode) {
        return Objects.requireNonNull(astNode, "astNode must not be null");
    }

    private static @NotNull Node requireAstNodeKey(@Nullable Object astNode) {
        Objects.requireNonNull(astNode, "astNode must not be null");
        if (astNode instanceof Node node) {
            return node;
        }
        throw new IllegalArgumentException(
                "astNode must be a gdparser Node, but got: " + astNode.getClass().getName()
        );
    }

    private static <T> @NotNull T requireValue(@Nullable T value) {
        return Objects.requireNonNull(value, "value must not be null");
    }
}
