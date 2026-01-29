package dev.superice.gdcc.scope;

import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.type.GdObjectType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ClassRegistryGdccTest {
    @Test
    void addFindRemoveGdccClassWorks() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);

        // Simple ClassDef implementation for testing
        var classDef = new LirClassDef("MyUserClass", "Object");

        // Ensure initially not present
        assertFalse(registry.isGdccClass("MyUserClass"));
        assertNull(registry.findGdccClass("MyUserClass"));

        // Add
        registry.addGdccClass(classDef);
        assertTrue(registry.isGdccClass("MyUserClass"));
        var found = registry.findGdccClass("MyUserClass");
        assertNotNull(found);
        assertEquals("MyUserClass", found.getName());

        // findType should return a GdObjectType and checkGdccType should be true
        var t = registry.findType("MyUserClass");
        assertNotNull(t);
        assertInstanceOf(GdObjectType.class, t);
        var got = (GdObjectType)t;
        assertTrue(got.checkGdccType(registry));

        // Remove
        var removed = registry.removeGdccClass("MyUserClass");
        assertNotNull(removed);
        assertEquals("MyUserClass", removed.getName());

        // After removal it should no longer be considered a gdcc class
        assertFalse(registry.isGdccClass("MyUserClass"));
        assertNull(registry.findGdccClass("MyUserClass"));

        var t2 = registry.findType("MyUserClass");
        assertNotNull(t2);
        assertInstanceOf(GdObjectType.class, t2);
        assertFalse(((GdObjectType)t2).checkGdccType(registry));
    }
}
