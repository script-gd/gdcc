package gd.script.gdcc.util.type;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedContainerAbiSupportTest {
    @Test
    void genericContainersAreAllowed() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertNull(TypedContainerAbiSupport.unsupportedConstructionReason(
                new GdArrayType(GdVariantType.VARIANT),
                registry
        ));
        assertNull(TypedContainerAbiSupport.unsupportedConstructionReason(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                registry
        ));
    }

    @Test
    void nestedTypedContainersAreRejected() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var reason = TypedContainerAbiSupport.unsupportedConstructionReason(
                new GdArrayType(new GdArrayType(GdIntType.INT)),
                registry
        );
        assertNotNull(reason);
        assertTrue(reason.contains("Nested typed container"));
    }

    @Test
    void nestedGenericArrayLeafIsAllowed() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertNull(TypedContainerAbiSupport.unsupportedConstructionReason(
                new GdArrayType(new GdArrayType(GdVariantType.VARIANT)),
                registry
        ));
    }

    @Test
    void voidLeafIsRejected() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var reason = TypedContainerAbiSupport.unsupportedConstructionReason(
                new GdArrayType(GdVoidType.VOID),
                registry
        );
        assertNotNull(reason);
        assertTrue(reason.contains("Void/compiler-only"));
    }

    @Test
    void engineObjectLeafIsAllowed() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertNull(TypedContainerAbiSupport.unsupportedConstructionReason(
                new GdArrayType(new GdObjectType("Node")),
                registry
        ));
        assertEquals(
                TypedContainerAbiSupport.LeafSupport.ALLOWED,
                TypedContainerAbiSupport.classifyArrayElementLeaf(new GdObjectType("Node"), registry)
        );
    }

    @Test
    void variantArrayElementIsRejectedAsTypedLeaf() {
        assertEquals(
                TypedContainerAbiSupport.LeafSupport.VARIANT_ARRAY_ELEMENT,
                TypedContainerAbiSupport.classifyArrayElementLeaf(GdVariantType.VARIANT, null)
        );
    }

    @Test
    void nullRegistryObjectLeafFailsClosed() {
        assertEquals(
                TypedContainerAbiSupport.LeafSupport.UNSUPPORTED_SCRIPT_LEAF,
                TypedContainerAbiSupport.classifyArrayElementLeaf(new GdObjectType("MyScript"), null)
        );
    }
}
