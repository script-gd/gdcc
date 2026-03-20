package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.resolver.ScopePropertyResolver;
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

class PropertyResolverParityTest {
    @Test
    @DisplayName("backend object-property adapter should match shared resolved owner/property")
    void objectPropertyAdapterShouldMatchSharedResolvedLookup() {
        var parentClass = new LirClassDef("ParentClass", "", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        childClass.addProperty(new LirPropertyDef("value", GdStringNameType.STRING_NAME));

        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(parentClass, childClass));
        var shared = ScopePropertyResolver.resolveObjectProperty(
                bodyBuilder.classRegistry(),
                new GdObjectType("ChildClass"),
                "value"
        );
        var sharedResolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, shared);

        var backendLookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("ChildClass"),
                "value",
                "LOAD_PROPERTY"
        );

        assertNotNull(backendLookup);
        assertEquals(ScopeOwnerKind.GDCC, sharedResolved.property().ownerKind());
        assertEquals(sharedResolved.property().ownerClass().getName(), backendLookup.ownerClass().getName());
        assertEquals(sharedResolved.property().property().getName(), backendLookup.property().getName());
        assertEquals(BackendPropertyAccessResolver.PropertyOwnerDispatchMode.GDCC, backendLookup.ownerDispatchMode());
    }

    @Test
    @DisplayName("backend object-property adapter should keep metadata-unknown fallback behavior")
    void objectPropertyAdapterShouldKeepMetadataUnknownFallback() {
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of());
        var shared = ScopePropertyResolver.resolveObjectProperty(
                bodyBuilder.classRegistry(),
                new GdObjectType("UnknownType"),
                "name"
        );

        assertInstanceOf(ScopePropertyResolver.MetadataUnknown.class, shared);
        assertNull(BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("UnknownType"),
                "name",
                "LOAD_PROPERTY"
        ));
    }

    @Test
    @DisplayName("backend builtin-property adapter should match shared builtin lookup")
    void builtinPropertyAdapterShouldMatchSharedLookup() {
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("length", "int", true, false, "0")),
                List.of()
        );
        var bodyBuilder = newBodyBuilder(apiWith(List.of(stringBuiltin), List.of()), List.of());

        var shared = ScopePropertyResolver.resolveBuiltinProperty(
                bodyBuilder.classRegistry(),
                bodyBuilder.classRegistry().resolveTypeMeta("String").instanceType(),
                "length"
        );
        var sharedResolved = assertInstanceOf(ScopePropertyResolver.Resolved.class, shared);

        var backendLookup = BackendPropertyAccessResolver.resolveBuiltinProperty(
                bodyBuilder,
                bodyBuilder.classRegistry().resolveTypeMeta("String").instanceType(),
                "length"
        );

        assertEquals(sharedResolved.property().ownerClass().getName(), backendLookup.builtinClass().getName());
        assertEquals(sharedResolved.property().property().getName(), backendLookup.property().getName());
    }

    @Test
    @DisplayName("backend object-property adapter should fail when shared resolver reports missing property")
    void objectPropertyAdapterShouldFailWhenSharedResolverReportsMissingProperty() {
        var parentClass = new LirClassDef("ParentClass", "", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(parentClass, childClass));

        var shared = ScopePropertyResolver.resolveObjectProperty(
                bodyBuilder.classRegistry(),
                new GdObjectType("ChildClass"),
                "missing_prop"
        );
        var failed = assertInstanceOf(ScopePropertyResolver.Failed.class, shared);
        assertEquals(ScopePropertyResolver.FailureKind.PROPERTY_MISSING, failed.kind());

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveObjectProperty(
                        bodyBuilder,
                        new GdObjectType("ChildClass"),
                        "missing_prop",
                        "STORE_PROPERTY"
                )
        );
        assertTrue(ex.getMessage().contains("missing_prop"));
        assertTrue(ex.getMessage().contains("ChildClass"));
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

    private static ExtensionAPI apiWith(List<ExtensionBuiltinClass> builtinClasses,
                                        List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), builtinClasses, gdClasses, List.of(), List.of());
    }

    private static ExtensionAPI emptyApi() {
        return apiWith(List.of(), List.of());
    }
}
