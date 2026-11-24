package gd.script.gdcc.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityHashSetTest {
    @Test
    void membershipEqualityAndHashUseReferenceIdentityAndAcceptNull() {
        var first = new Value(1);
        var second = new Value(1);
        var equalOnly = new Value(1);
        var actual = new IdentityHashSet<Value>();
        var reference = jdkIdentitySet();
        for (var set : List.of(actual, reference)) {
            assertTrue(set.add(first));
            assertTrue(set.add(second));
            assertFalse(set.add(first));
            assertTrue(set.add(null));
            assertFalse(set.add(null));
            assertEquals(3, set.size());
            assertTrue(set.contains(null));
            assertFalse(set.contains(equalOnly));
            assertFalse(set.remove(equalOnly));
            assertEquals(System.identityHashCode(first) + System.identityHashCode(second), set.hashCode());
        }
        assertEquals(reference, actual);
        assertEquals(actual, reference);
        assertEquals(reference.spliterator().characteristics(), actual.spliterator().characteristics());
        assertTrue(actual.remove(null));
        assertFalse(actual.contains(null));
        assertFalse(reference.equals(actual));
    }

    @Test
    void bulkOperationsRespectTheOtherCollectionsMembershipRule() {
        var first = new Value(1);
        var second = new Value(1);
        var equalOnly = new Value(1);
        var distinct = new Value(2);
        var equalByValue = List.of(equalOnly);
        var referenceOnly = jdkIdentitySet();
        referenceOnly.add(first);

        var actual = new IdentityHashSet<Value>();
        var reference = jdkIdentitySet();
        for (var set : List.of(actual, reference)) {
            var seed = List.of(first, second, distinct);
            assertTrue(set.addAll(seed));
            assertFalse(set.containsAll(equalByValue));
            assertTrue(set.retainAll(equalByValue));
            assertTrue(set.containsAll(List.of(first, second)));
            assertEquals(2, set.size());
            assertTrue(set.removeAll(equalByValue));
            assertTrue(set.isEmpty());

            assertTrue(set.addAll(seed));
            assertTrue(set.retainAll(referenceOnly));
            assertEquals(1, set.size());
            assertTrue(set.contains(first));
            assertFalse(set.contains(second));
            assertTrue(set.removeAll(referenceOnly));
            assertTrue(set.isEmpty());
        }
    }

    @Test
    void iteratorRemovalMutatesTheSetButNotAnIndependentCopy() {
        var first = new Value(1);
        var second = new Value(1);
        var actual = new IdentityHashSet<Value>();
        var reference = jdkIdentitySet();
        for (var set : List.of(actual, reference)) {
            set.addAll(List.of(first, second));
            var copy = new IdentityHashSet<Value>();
            copy.addAll(set);
            var iterator = set.iterator();
            var removed = iterator.next();
            iterator.remove();
            assertEquals(1, set.size());
            assertFalse(set.contains(removed));
            assertEquals(2, copy.size());
            assertTrue(copy.contains(removed));
        }
    }

    @Test
    void serializationRoundTripPreservesEqualButDistinctElementsAndNull() throws Exception {
        var first = new Value(1);
        var second = new Value(1);
        for (var original : List.of(new IdentityHashSet<Value>(), jdkIdentitySet())) {
            Collections.addAll(original, first, second, null);
            var restored = serializationRoundTrip(original);
            assertEquals(original.getClass(), restored.getClass());
            assertTrue(restored.contains(null));
            assertFalse(restored.contains(first));
            var values = restored.stream().filter(Objects::nonNull).toList();
            assertEquals(List.of(first, second), values);
            assertNotSame(values.getFirst(), values.getLast());
        }
    }

    private static Set<Value> jdkIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<?> serializationRoundTrip(Set<Value> set) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(set);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return assertInstanceOf(Set.class, input.readObject());
        }
    }

    private record Value(int number) implements Serializable {
    }
}
