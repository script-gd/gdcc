package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;

/// A mutable set backed by the JDK identity map. Membership compares references, including
/// `null`, rather than calling element `equals`. Equality with ordinary sets need not be
/// symmetric. Iteration order is unspecified.
public final class IdentityHashSet<E> extends AbstractSet<E> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Set<E> elements = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public boolean contains(Object element) {
        return elements.contains(element);
    }

    @Override
    public boolean add(E element) {
        return elements.add(element);
    }

    @Override
    public boolean remove(Object element) {
        return elements.remove(element);
    }

    @Override
    public void clear() {
        elements.clear();
    }

    /// The identity map's key set tests each stored element against the argument's membership
    /// rule. The AbstractSet size optimization could instead test the reverse membership.
    @Override
    public boolean removeAll(Collection<?> collection) {
        return elements.removeAll(collection);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        return elements.retainAll(collection);
    }

    @Override
    public @NotNull Iterator<E> iterator() {
        return elements.iterator();
    }

    @Override
    public @NotNull Spliterator<E> spliterator() {
        return elements.spliterator();
    }

    @Override
    public Object @NotNull [] toArray() {
        return elements.toArray();
    }

    @Override
    public <T> T @NotNull [] toArray(T @NotNull [] array) {
        return elements.toArray(array);
    }

    @Override
    public boolean equals(Object other) {
        return other == this || elements.equals(other);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return elements.toString();
    }
}
