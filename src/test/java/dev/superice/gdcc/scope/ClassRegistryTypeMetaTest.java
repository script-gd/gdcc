package dev.superice.gdcc.scope;

import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassRegistryTypeMetaTest {
    @Test
    void resolveBuiltinAndEngineTypeMetaStrictly() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var stringType = registry.resolveTypeMeta("String");
        assertNotNull(stringType);
        assertEquals(ScopeTypeMetaKind.BUILTIN, stringType.kind());
        assertEquals("String", stringType.canonicalName());
        assertEquals("String", stringType.sourceName());
        assertEquals(GdStringType.STRING, stringType.instanceType());

        var nodeType = registry.resolveTypeMeta("Node");
        assertNotNull(nodeType);
        assertEquals(ScopeTypeMetaKind.ENGINE_CLASS, nodeType.kind());
        assertEquals("Node", nodeType.canonicalName());
        assertEquals("Node", nodeType.sourceName());
        assertInstanceOf(GdObjectType.class, nodeType.instanceType());
        assertEquals("Node", nodeType.instanceType().getTypeName());
    }

    @Test
    void resolveGdccTypeAndGlobalEnumStrictly() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        registry.addGdccClass(new LirClassDef("MyUserClass", "Object"));

        var gdccType = registry.resolveTypeMeta("MyUserClass");
        assertNotNull(gdccType);
        assertEquals(ScopeTypeMetaKind.GDCC_CLASS, gdccType.kind());
        assertEquals("MyUserClass", gdccType.canonicalName());
        assertEquals("MyUserClass", gdccType.sourceName());
        assertEquals("MyUserClass", gdccType.instanceType().getTypeName());

        if (!api.globalEnums().isEmpty()) {
            var enumName = api.globalEnums().getFirst().name();
            var enumType = registry.resolveTypeMeta(enumName);
            assertNotNull(enumType);
            assertEquals(ScopeTypeMetaKind.GLOBAL_ENUM, enumType.kind());
            assertEquals(enumName, enumType.canonicalName());
            assertEquals(enumName, enumType.sourceName());
            assertEquals(GdIntType.INT, enumType.instanceType());
            assertTrue(enumType.pseudoType());
        }
    }

    @Test
    void resolveInnerGdccTypeMetaUsesSourceNameOverrideWithoutCreatingGlobalAlias() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("Outer", "Object"));
        registry.addGdccClass(new LirClassDef("Outer$Inner", "Object"), "Inner");

        var innerType = registry.resolveTypeMeta("Outer$Inner");
        assertNotNull(innerType);
        assertEquals(ScopeTypeMetaKind.GDCC_CLASS, innerType.kind());
        assertEquals("Outer$Inner", innerType.canonicalName());
        assertEquals("Inner", innerType.sourceName());
        assertEquals("Outer$Inner", innerType.instanceType().getTypeName());

        assertNull(registry.findGdccClassSourceNameOverride("Outer"));
        assertEquals("Inner", registry.findGdccClassSourceNameOverride("Outer$Inner"));
        assertNull(registry.resolveTypeMeta("Inner"));
    }

    @Test
    void resolveStrictContainerTypeMetaRecursively() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(new LirClassDef("InventoryItem", "Object"));

        var arrayMeta = registry.resolveTypeMeta("Array[InventoryItem]");
        assertNotNull(arrayMeta);
        assertEquals(ScopeTypeMetaKind.BUILTIN, arrayMeta.kind());
        var arrayType = assertInstanceOf(GdArrayType.class, arrayMeta.instanceType());
        assertEquals("InventoryItem", arrayType.getValueType().getTypeName());

        var dictionaryMeta = registry.resolveTypeMeta("Dictionary[String, InventoryItem]");
        assertNotNull(dictionaryMeta);
        var dictionaryType = assertInstanceOf(GdDictionaryType.class, dictionaryMeta.instanceType());
        assertEquals(GdStringType.STRING, dictionaryType.getKeyType());
        assertEquals("InventoryItem", dictionaryType.getValueType().getTypeName());
    }

    @Test
    void resolveTypeMetaRejectsUnknownUtilityAndSingletonNames() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);

        if (!api.utilityFunctions().isEmpty()) {
            assertNull(registry.resolveTypeMeta(api.utilityFunctions().getFirst().name()));
        }
        if (!api.singletons().isEmpty()) {
            var singletonName = api.singletons().getFirst().name();
            if (!registry.isGdClass(singletonName) && !registry.isGdccClass(singletonName)) {
                assertNull(registry.resolveTypeMeta(singletonName));
            }
        }
        assertNull(registry.resolveTypeMeta("DefinitelyMissingType"));
    }
}
