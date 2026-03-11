package dev.superice.gdcc.frontend.sema;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAstSideTableTest {
    @Test
    void behavesAsMapWhilePreservingIdentityKeySemantics() {
        Map<Object, String> sideTable = new FrontendAstSideTable<>();
        var firstKey = new String("node");
        var secondKey = new String("node");
        assertNotSame(firstKey, secondKey);

        sideTable.put(firstKey, "value");

        assertEquals("value", sideTable.get(firstKey));
        assertTrue(sideTable.containsKey(firstKey));
        assertNull(sideTable.get(secondKey));
        assertFalse(sideTable.containsKey(secondKey));
        assertEquals(1, sideTable.size());
    }

    @Test
    void supportsBulkPutAllFromMapAndTypedSideTable() {
        var sideTable = new FrontendAstSideTable<String>();
        var mapKey = new Object();
        var typedKey = new Object();
        var other = new FrontendAstSideTable<String>();
        other.put(typedKey, "typed");

        sideTable.put(mapKey, "mapped");
        sideTable.putAll(other);

        assertEquals("mapped", sideTable.get(mapKey));
        assertEquals("typed", sideTable.get(typedKey));
        assertEquals(2, sideTable.size());
    }

    @Test
    void exposesBackedMapViewsForConvenientMutation() {
        Map<Object, String> sideTable = new FrontendAstSideTable<>();
        var key = new Object();
        sideTable.put(key, "initial");

        var entry = sideTable.entrySet().iterator().next();
        entry.setValue("updated");
        assertEquals("updated", sideTable.get(key));

        sideTable.keySet().remove(key);
        assertTrue(sideTable.isEmpty());
    }

    @Test
    void supportsStandardMapDefaultingAndComputeOperations() {
        Map<Object, String> sideTable = new FrontendAstSideTable<>();
        var key = new Object();

        assertEquals("fallback", sideTable.getOrDefault(key, "fallback"));

        sideTable.compute(key, (_, existing) -> existing == null ? "created" : existing + "!");
        assertEquals("created", sideTable.get(key));

        sideTable.compute(key, (_, existing) -> existing == null ? "missing" : existing + "!");
        assertEquals("created!", sideTable.get(key));
    }

    @Test
    void rejectsNullKeysAndValuesAcrossMapOperations() {
        var sideTable = new FrontendAstSideTable<String>();
        var key = new Object();
        sideTable.put(key, "value");

        assertThrows(NullPointerException.class, () -> sideTable.put(null, "value"));
        assertThrows(NullPointerException.class, () -> sideTable.put(key, null));
        assertThrows(NullPointerException.class, () -> sideTable.get(null));
        assertThrows(NullPointerException.class, () -> sideTable.containsKey(null));
        assertThrows(NullPointerException.class, () -> sideTable.containsValue(null));
        assertThrows(NullPointerException.class, () -> sideTable.remove(null));
        assertThrows(NullPointerException.class, () -> sideTable.putAll((Map<Object, String>) null));
        assertThrows(NullPointerException.class, () -> sideTable.putAll((FrontendAstSideTable<String>) null));
    }
}
