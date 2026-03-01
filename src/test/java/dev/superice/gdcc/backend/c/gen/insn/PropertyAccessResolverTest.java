package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyAccessResolverTest {
    @Test
    @DisplayName("resolveObjectProperty should return null for unknown object receiver")
    void resolveObjectPropertyReturnsNullForUnknownReceiver() {
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(hostClass));

        var lookup = PropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("UnknownType"),
                "name",
                "load_property"
        );

        assertNull(lookup);
    }

    @Test
    @DisplayName("resolveObjectProperty should pick nearest shadowed property and classify owner as GDCC")
    void resolveObjectPropertyPicksNearestShadowedGdccOwner() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        childClass.addProperty(new LirPropertyDef("value", GdStringNameType.STRING_NAME));

        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(parentClass, childClass));

        var lookup = PropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("ChildClass"),
                "value",
                "load_property"
        );

        assertNotNull(lookup);
        assertEquals("ChildClass", lookup.ownerClass().getName());
        assertEquals("StringName", lookup.property().getType().getTypeName());
        assertEquals(PropertyAccessResolver.PropertyOwnerDispatchMode.GDCC, lookup.ownerDispatchMode());
    }

    @Test
    @DisplayName("resolveObjectProperty should classify ENGINE owner on GDCC->ENGINE chain")
    void resolveObjectPropertyClassifiesEngineOwner() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "")),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var userClass = new LirClassDef("MyClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(api, List.of(userClass));

        var lookup = PropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("MyClass"),
                "name",
                "load_property"
        );

        assertNotNull(lookup);
        assertEquals("Node", lookup.ownerClass().getName());
        assertEquals(PropertyAccessResolver.PropertyOwnerDispatchMode.ENGINE, lookup.ownerDispatchMode());
    }

    @Test
    @DisplayName("resolveObjectProperty should fail-fast when property is absent in whole hierarchy")
    void resolveObjectPropertyFailsWhenPropertyMissing() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(parentClass, childClass));

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> PropertyAccessResolver.resolveObjectProperty(
                        bodyBuilder,
                        new GdObjectType("ChildClass"),
                        "missing_prop",
                        "store_property"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("missing_prop"));
        assertTrue(ex.getMessage().contains("ChildClass"));
    }

    @Test
    @DisplayName("resolveObjectProperty should fail-fast on inheritance cycle")
    void resolveObjectPropertyFailsOnInheritanceCycle() {
        var classA = new LirClassDef("ClassA", "ClassB", false, false, Map.of(), List.of(), List.of(), List.of());
        var classB = new LirClassDef("ClassB", "ClassA", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(classA, classB));

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> PropertyAccessResolver.resolveObjectProperty(
                        bodyBuilder,
                        new GdObjectType("ClassA"),
                        "name",
                        "load_property"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("inheritance cycle"));
    }

    @Test
    @DisplayName("resolveObjectProperty should fail-fast when super metadata is missing")
    void resolveObjectPropertyFailsWhenSuperMetadataMissing() {
        var classA = new LirClassDef("ClassA", "MissingBase", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(classA));

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> PropertyAccessResolver.resolveObjectProperty(
                        bodyBuilder,
                        new GdObjectType("ClassA"),
                        "name",
                        "store_property"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("MissingBase"));
    }

    private static CBodyBuilder newBodyBuilder(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }

        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var context = new CodegenContext(projectInfo, classRegistry);
        var helper = new CGenHelper(context, gdccClasses);

        var ownerClass = gdccClasses.isEmpty()
                ? new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of())
                : gdccClasses.getFirst();

        var func = new LirFunctionDef("test_func");
        func.setReturnType(GdVoidType.VOID);
        return new CBodyBuilder(helper, ownerClass, func);
    }

    private static ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
