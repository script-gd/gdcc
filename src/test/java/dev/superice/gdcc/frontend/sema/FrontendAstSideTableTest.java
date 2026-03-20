package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAstSideTableTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void behavesAsMapWhilePreservingIdentityKeySemantics() {
        Map<Node, String> sideTable = new FrontendAstSideTable<>();
        var firstKey = passNode();
        var secondKey = passNode();
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
        var mapKey = passNode();
        var typedKey = passNode();
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
        Map<Node, String> sideTable = new FrontendAstSideTable<>();
        var key = passNode();
        sideTable.put(key, "initial");

        var entry = sideTable.entrySet().iterator().next();
        entry.setValue("updated");
        assertEquals("updated", sideTable.get(key));

        sideTable.keySet().remove(key);
        assertTrue(sideTable.isEmpty());
    }

    @Test
    void supportsStandardMapDefaultingAndComputeOperations() {
        Map<Node, String> sideTable = new FrontendAstSideTable<>();
        var key = passNode();

        assertEquals("fallback", sideTable.getOrDefault(key, "fallback"));

        sideTable.compute(key, (_, existing) -> existing == null ? "created" : existing + "!");
        assertEquals("created", sideTable.get(key));

        sideTable.compute(key, (_, existing) -> existing == null ? "missing" : existing + "!");
        assertEquals("created!", sideTable.get(key));
    }

    @Test
    void rejectsNullNonNodeKeysAndValuesAcrossMapOperations() {
        var sideTable = new FrontendAstSideTable<String>();
        var key = passNode();
        sideTable.put(key, "value");
        @SuppressWarnings({"rawtypes", "unchecked"})
        var rawMap = (Map) sideTable;

        assertThrows(NullPointerException.class, () -> sideTable.put(null, "value"));
        assertThrows(NullPointerException.class, () -> sideTable.put(key, null));
        assertThrows(NullPointerException.class, () -> sideTable.get(null));
        assertThrows(NullPointerException.class, () -> sideTable.containsKey(null));
        assertThrows(NullPointerException.class, () -> sideTable.containsValue(null));
        assertThrows(NullPointerException.class, () -> sideTable.remove(null));
        assertThrows(IllegalArgumentException.class, () -> rawMap.get("not a node"));
        assertThrows(IllegalArgumentException.class, () -> rawMap.containsKey("not a node"));
        assertThrows(IllegalArgumentException.class, () -> rawMap.remove("not a node"));
        assertThrows(NullPointerException.class, () -> sideTable.putAll((Map<Node, String>) null));
        assertThrows(NullPointerException.class, () -> sideTable.putAll((FrontendAstSideTable<String>) null));
    }

    private static PassStatement passNode() {
        return new PassStatement(RANGE);
    }
}
