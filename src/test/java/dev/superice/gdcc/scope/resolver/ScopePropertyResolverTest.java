package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopePropertyResolverTest {
    @Test
    @DisplayName("shared object property resolver should report metadata unknown for unknown receiver")
    void resolveObjectPropertyShouldReportMetadataUnknownForUnknownReceiver() {
        var registry = newRegistry(emptyApi(), List.of());

        var result = ScopePropertyResolver.resolveObjectProperty(
                registry,
                new GdObjectType("UnknownType"),
                "name"
        );

        var metadataUnknown = assertInstanceOf(ScopePropertyResolver.MetadataUnknown.class, result);
        assertEquals("UnknownType", metadataUnknown.receiverType().getTypeName());
        assertEquals("name", metadataUnknown.propertyName());
    }

    @Test
    @DisplayName("shared object property resolver should pick nearest GDCC owner")
    void resolveObjectPropertyShouldPickNearestGdccOwner() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        childClass.addProperty(new LirPropertyDef("value", GdStringNameType.STRING_NAME));

        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));
        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("ChildClass"), "value");

        var resolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, result);
        assertEquals(ScopeOwnerKind.GDCC, resolved.property().ownerKind());
        assertEquals("ChildClass", resolved.property().ownerClass().getName());
        assertEquals("StringName", resolved.property().property().getType().getTypeName());
    }

    @Test
    @DisplayName("shared object property resolver should follow canonical inner-class superclass names")
    void resolveObjectPropertyShouldFollowCanonicalInnerSuperclassNames() {
        var parentClass = new LirClassDef("Outer__sub__Shared", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("Outer__sub__Leaf", "Outer__sub__Shared", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));
        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("Outer__sub__Leaf"), "value");

        var resolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, result);
        assertEquals("Outer__sub__Shared", resolved.property().ownerClass().getName());
        assertEquals("String", resolved.property().property().getType().getTypeName());
    }

    @Test
    @DisplayName("shared object property resolver should follow mapped canonical inner-class superclass names")
    void resolveObjectPropertyShouldFollowMappedCanonicalInnerSuperclassNames() {
        var parentClass = new LirClassDef("RuntimeOuter__sub__Shared", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("RuntimeOuter__sub__Leaf", "RuntimeOuter__sub__Shared", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(
                emptyApi(),
                List.of(parentClass, childClass),
                Map.of(
                        "RuntimeOuter__sub__Shared", "Shared",
                        "RuntimeOuter__sub__Leaf", "Leaf"
                )
        );
        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("RuntimeOuter__sub__Leaf"), "value");

        var resolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, result);
        assertEquals("RuntimeOuter__sub__Shared", resolved.property().ownerClass().getName());
        assertEquals("String", resolved.property().property().getType().getTypeName());
    }

    @Test
    @DisplayName("shared object property resolver should classify engine owner")
    void resolveObjectPropertyShouldClassifyEngineOwner() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
                List.of()
        );
        var userClass = new LirClassDef("MyClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(apiWith(List.of(), List.of(nodeClass)), List.of(userClass));

        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("MyClass"), "name");
        var resolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, result);

        assertEquals(ScopeOwnerKind.ENGINE, resolved.property().ownerKind());
        assertEquals("Node", resolved.property().ownerClass().getName());
    }

    @Test
    @DisplayName("shared builtin property resolver should resolve builtin property from strict type-meta instance type")
    void resolveBuiltinPropertyShouldUseStrictTypeMetaInstanceType() {
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.MemberInfo("length", "int")),
                List.of()
        );
        var registry = newRegistry(apiWith(List.of(stringBuiltin), List.of()), List.of());
        var stringTypeMeta = registry.resolveTypeMeta("String");
        assertNotNull(stringTypeMeta);

        var result = ScopePropertyResolver.resolveBuiltinProperty(
                registry,
                stringTypeMeta.instanceType(),
                "length"
        );

        var resolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, result);
        assertEquals(ScopeOwnerKind.BUILTIN, resolved.property().ownerKind());
        assertEquals("String", resolved.property().ownerClass().getName());
        assertEquals("length", resolved.property().property().getName());
    }

    @Test
    @DisplayName("shared builtin property resolver should resolve member-backed builtin properties from default API")
    void resolveBuiltinPropertyShouldResolveMemberBackedBuiltinPropertiesFromDefaultApi() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        assertBuiltinPropertyResolved(registry, "Vector3", "x", "float");
        assertBuiltinPropertyResolved(registry, "Color", "r", "float");
    }

    @Test
    @DisplayName("shared builtin property resolver should still report missing builtin member on default API")
    void resolveBuiltinPropertyShouldStillReportMissingBuiltinMemberOnDefaultApi() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var vector3Type = registry.findType("Vector3");
        assertNotNull(vector3Type);

        var result = ScopePropertyResolver.resolveBuiltinProperty(registry, vector3Type, "missing_axis");
        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, result);

        assertEquals(ScopePropertyResolver.FailureKind.BUILTIN_PROPERTY_MISSING, failed.kind());
        assertEquals("Vector3", failed.ownerClassName());
        assertEquals("missing_axis", failed.propertyName());
    }

    @Test
    @DisplayName("shared object property resolver should report missing property in known hierarchy")
    void resolveObjectPropertyShouldReportMissingProperty() {
        var parentClass = new LirClassDef("ParentClass", "", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));

        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("ChildClass"), "missing_prop");
        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, result);

        assertEquals(ScopePropertyResolver.FailureKind.PROPERTY_MISSING, failed.kind());
        assertEquals(List.of("ChildClass", "ParentClass"), failed.hierarchy());
    }

    @Test
    @DisplayName("shared object property resolver should report missing super metadata")
    void resolveObjectPropertyShouldReportMissingSuperMetadata() {
        var classA = new LirClassDef("ClassA", "MissingBase", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(classA));

        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("ClassA"), "name");
        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, result);

        assertEquals(ScopePropertyResolver.FailureKind.MISSING_SUPER_METADATA, failed.kind());
        assertEquals("MissingBase", failed.relatedClassName());
    }

    @Test
    @DisplayName("shared object property resolver should reject stale source-styled inner superclass names")
    void resolveObjectPropertyShouldRejectSourceStyledInnerSuperclassNames() {
        var parentClass = new LirClassDef("Outer__sub__Shared", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("Outer__sub__Leaf", "Shared", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(parentClass, childClass));
        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("Outer__sub__Leaf"), "value");

        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, result);
        assertEquals(ScopePropertyResolver.FailureKind.MISSING_SUPER_METADATA, failed.kind());
        assertEquals("Shared", failed.relatedClassName());
    }

    @Test
    @DisplayName("shared object property resolver should reject stale source-styled mapped inner superclass names")
    void resolveObjectPropertyShouldRejectSourceStyledMappedInnerSuperclassNames() {
        var parentClass = new LirClassDef("RuntimeOuter__sub__Shared", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("RuntimeOuter__sub__Leaf", "Shared", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(
                emptyApi(),
                List.of(parentClass, childClass),
                Map.of(
                        "RuntimeOuter__sub__Shared", "Shared",
                        "RuntimeOuter__sub__Leaf", "Leaf"
                )
        );
        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("RuntimeOuter__sub__Leaf"), "value");

        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, result);
        assertEquals(ScopePropertyResolver.FailureKind.MISSING_SUPER_METADATA, failed.kind());
        assertEquals("Shared", failed.relatedClassName());
    }

    @Test
    @DisplayName("shared object property resolver should report inheritance cycle")
    void resolveObjectPropertyShouldReportInheritanceCycle() {
        var classA = new LirClassDef("ClassA", "ClassB", false, false, Map.of(), List.of(), List.of(), List.of());
        var classB = new LirClassDef("ClassB", "ClassA", false, false, Map.of(), List.of(), List.of(), List.of());
        var registry = newRegistry(emptyApi(), List.of(classA, classB));

        var result = ScopePropertyResolver.resolveObjectProperty(registry, new GdObjectType("ClassA"), "name");
        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, result);

        assertEquals(ScopePropertyResolver.FailureKind.INHERITANCE_CYCLE, failed.kind());
        assertTrue(failed.hierarchy().contains("ClassA"));
        assertTrue(failed.hierarchy().contains("ClassB"));
    }

    private static ClassRegistry newRegistry(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        return newRegistry(api, gdccClasses, Map.of());
    }

    private static ClassRegistry newRegistry(
            ExtensionAPI api,
            List<LirClassDef> gdccClasses,
            Map<String, String> sourceNameOverrides
    ) {
        var registry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            registry.addGdccClass(gdccClass, sourceNameOverrides.get(gdccClass.getName()));
        }
        return registry;
    }

    private static ExtensionAPI apiWith(List<ExtensionBuiltinClass> builtinClasses,
                                        List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), builtinClasses, gdClasses, List.of(), List.of());
    }

    private static ExtensionAPI emptyApi() {
        return apiWith(List.of(), List.of());
    }

    private static void assertBuiltinPropertyResolved(
            ClassRegistry registry,
            String builtinName,
            String propertyName,
            String expectedTypeName
    ) {
        var builtinType = registry.findType(builtinName);
        assertNotNull(builtinType);
        var result = ScopePropertyResolver.resolveBuiltinProperty(registry, builtinType, propertyName);
        var resolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, result);
        assertEquals(ScopeOwnerKind.BUILTIN, resolved.property().ownerKind());
        assertEquals(builtinName, resolved.property().ownerClass().getName());
        assertEquals(propertyName, resolved.property().property().getName());
        assertEquals(expectedTypeName, resolved.property().property().getType().getTypeName());
    }
}
