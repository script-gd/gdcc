package dev.superice.gdcc.scope;

import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.type.GdObjectType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

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
        var got = (GdObjectType) t;
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
        assertFalse(((GdObjectType) t2).checkGdccType(registry));
    }

    @Test
    void addAndRemoveInnerGdccClassKeepsSourceNameOverrideSideTableInSync() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        var innerClassDef = new LirClassDef("Outer__sub__Inner", "Object");

        registry.addGdccClass(innerClassDef, "Inner");
        assertTrue(registry.isGdccClass("Outer__sub__Inner"));
        assertEquals("Inner", registry.findGdccClassSourceNameOverride("Outer__sub__Inner"));
        assertNull(registry.resolveTypeMeta("Inner"));

        var innerMeta = registry.resolveTypeMeta("Outer__sub__Inner");
        assertNotNull(innerMeta);
        assertEquals("Outer__sub__Inner", innerMeta.canonicalName());
        assertEquals("Inner", innerMeta.sourceName());
        assertEquals("Outer__sub__Inner", innerMeta.displayName());

        var removed = registry.removeGdccClass("Outer__sub__Inner");
        assertSame(innerClassDef, removed);
        assertNull(registry.findGdccClassSourceNameOverride("Outer__sub__Inner"));
        assertNull(registry.findGdccClass("Outer__sub__Inner"));
        assertNull(registry.resolveTypeMeta("Outer__sub__Inner"));
    }

    @Test
    void canonicalInnerSuperclassNamesDriveAssignabilityAndRefCountedStatus() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        var parentClass = new LirClassDef("Outer__sub__Shared", "RefCounted");
        var childClass = new LirClassDef("Outer__sub__Leaf", "Outer__sub__Shared");
        var brokenChildClass = new LirClassDef("Outer__sub__BrokenLeaf", "Shared");

        registry.addGdccClass(parentClass, "Shared");
        registry.addGdccClass(childClass, "Leaf");
        registry.addGdccClass(brokenChildClass, "BrokenLeaf");

        assertTrue(registry.checkAssignable(
                new GdObjectType("Outer__sub__Leaf"),
                new GdObjectType("Outer__sub__Shared")
        ));
        assertFalse(registry.checkAssignable(
                new GdObjectType("Outer__sub__BrokenLeaf"),
                new GdObjectType("Outer__sub__Shared")
        ));
        assertEquals(RefCountedStatus.YES, registry.getRefCountedStatus(new GdObjectType("Outer__sub__Leaf")));
        assertEquals(RefCountedStatus.NO, registry.getRefCountedStatus(new GdObjectType("Outer__sub__BrokenLeaf")));
    }

    @Test
    void canonicalMappedTopLevelSuperclassNamesDriveAssignabilityAndRefCountedStatus() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        var parentClass = new LirClassDef("RuntimeBase", "RefCounted");
        var childClass = new LirClassDef("RuntimeLeaf", "RuntimeBase");
        var brokenChildClass = new LirClassDef("RuntimeBrokenLeaf", "BaseBySource");

        registry.addGdccClass(parentClass, "BaseBySource");
        registry.addGdccClass(childClass, "LeafBySource");
        registry.addGdccClass(brokenChildClass, "BrokenLeafBySource");

        assertTrue(registry.checkAssignable(
                new GdObjectType("RuntimeLeaf"),
                new GdObjectType("RuntimeBase")
        ));
        assertFalse(registry.checkAssignable(
                new GdObjectType("RuntimeBrokenLeaf"),
                new GdObjectType("RuntimeBase")
        ));
        assertEquals(RefCountedStatus.YES, registry.getRefCountedStatus(new GdObjectType("RuntimeLeaf")));
        assertEquals(RefCountedStatus.NO, registry.getRefCountedStatus(new GdObjectType("RuntimeBrokenLeaf")));
    }

    @Test
    void addMappedTopLevelGdccClassKeepsSourceNameOverrideSideTableInSync() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        var mappedClassDef = new LirClassDef("RuntimeOuter", "Object");

        registry.addGdccClass(mappedClassDef, "MappedOuter");
        assertTrue(registry.isGdccClass("RuntimeOuter"));
        assertEquals("MappedOuter", registry.findGdccClassSourceNameOverride("RuntimeOuter"));
        assertNull(registry.resolveTypeMeta("MappedOuter"));

        var mappedMeta = registry.resolveTypeMeta("RuntimeOuter");
        assertNotNull(mappedMeta);
        assertEquals("RuntimeOuter", mappedMeta.canonicalName());
        assertEquals("MappedOuter", mappedMeta.sourceName());
        assertEquals("RuntimeOuter", mappedMeta.displayName());

        var removed = registry.removeGdccClass("RuntimeOuter");
        assertSame(mappedClassDef, removed);
        assertNull(registry.findGdccClassSourceNameOverride("RuntimeOuter"));
        assertNull(registry.findGdccClass("RuntimeOuter"));
        assertNull(registry.resolveTypeMeta("RuntimeOuter"));
    }
}
