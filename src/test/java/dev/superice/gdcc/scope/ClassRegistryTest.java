package dev.superice.gdcc.scope;

import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ClassRegistryTest {
    @Test
    void registryIndexesApiAndResolvesEntries() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        assertNotNull(api);

        var registry = new ClassRegistry(api);

        // Check a known builtin class exists (String)
        var t = registry.findType("String");
        assertNotNull(t, "String type should be resolvable");
        assertEquals("String", t.getTypeName());
        // builtin types should resolve to concrete GdType singletons, but some names may resolve to GdObjectType as engine classes
        if (t instanceof GdObjectType) {
            assertInstanceOf(GdObjectType.class, t);
            assertTrue(((GdObjectType) t).checkEngineType(registry));
        }

        // Check a known gd class exists (take first class from API)
        assertFalse(api.classes().isEmpty(), "API should contain at least one gd class");
        var someGd = api.classes().getFirst();
        assertNotNull(someGd.name());
        var tg = registry.findType(someGd.name());
        assertNotNull(tg, () -> "GdClass type for " + someGd.name() + " should be resolvable");
        assertEquals(someGd.name(), tg.getTypeName());
        assertInstanceOf(GdObjectType.class, tg);
        assertTrue(((GdObjectType) tg).checkEngineType(registry));

        // Utility function signature (if present)
        if (!api.utilityFunctions().isEmpty()) {
            var uf = api.utilityFunctions().getFirst();
            assertNotNull(uf.name());
            var sig = registry.findUtilityFunctionSignature(uf.name());
            assertNotNull(sig, "Utility function signature should be found");
            assertEquals(uf.name(), sig.name());
            var expectedArgCount = uf.arguments() == null ? 0 : uf.arguments().size();
            assertEquals(expectedArgCount, sig.parameterCount());

            // if API has argument type strings, ensure parameter types were resolved to GdType when possible
            if (uf.arguments() != null && !uf.arguments().isEmpty()) {
                var firstArg = sig.parameters().getFirst();
                // type may be null if argument had no declared type in API; otherwise ensure it has a name
                if (firstArg.type() != null) {
                    assertNotNull(firstArg.type().getTypeName());
                }
            }

            // return type resolved (may be null)
            if (sig.returnType() != null) {
                assertNotNull(sig.returnType().getTypeName());
            }
        }

        // Singleton (if present) - ensure singleton name maps to its declared type
        if (!api.singletons().isEmpty()) {
            var s = api.singletons().getFirst();
            assertNotNull(s.name());
            var st = registry.findSingletonType(s.name());
            if (s.type() != null) {
                assertNotNull(st);
                assertEquals(s.type(), st.getTypeName());
                assertTrue(st.checkEngineType(registry));
            } else {
                assertNull(st);
            }
        }
    }

    @Test
    void builtinTypesShouldNotBeGdObjectType() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);

        var builtinNames = List.of(
                "int", "float", "bool", "String", "Vector2", "Vector3", "Vector4",
                "Array", "Dictionary", "Variant", "void", "Nil", "StringName", "NodePath",
                "RID", "PackedInt32Array", "PackedFloat32Array", "Plane", "Quaternion", "Color"
        );

        for (var name : builtinNames) {
            var ty = registry.findType(name);
            assertNotNull(ty, () -> "Expected type for builtin name: " + name);
            assertFalse(ty instanceof GdObjectType, () -> "Builtin type '" + name + "' must not be a GdObjectType");
        }
    }

    @Test
    void findTypeDoesNotReturnForSingletonEnumOrFunction() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);

        // If global enums exist, findType should not return a type for the enum name
        if (!api.globalEnums().isEmpty()) {
            var ge = api.globalEnums().getFirst();
            assertNotNull(ge.name());
            assertNull(registry.findType(ge.name()), () -> "findType should not return type for global enum name: " + ge.name());
        }

        // If utility functions exist, findType should not return a type for the function name
        if (!api.utilityFunctions().isEmpty()) {
            var uf = api.utilityFunctions().getFirst();
            assertNotNull(uf.name());
            assertNull(registry.findType(uf.name()), () -> "findType should not return type for utility function name: " + uf.name());
        }
    }

    @Test
    void findVirtualMethods() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        var vMethodMap = registry.getVirtualMethods("Node3D");
        var expectSet = Set.of(
                "_get_focused_accessibility_element", "_enter_tree", "_unhandled_key_input",
                "_ready", "_shortcut_input", "_physics_process", "_process", "_input",
                "_get_accessibility_configuration_warnings", "_unhandled_input", "_exit_tree",
                "_get_configuration_warnings"
        );
        for (var expectName : expectSet) {
            assertTrue(vMethodMap.containsKey(expectName), () -> "Expected virtual method not found: " + expectName);
        }
    }

    @Test
    void checkAssignableSupportsContainerCovariance() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertTrue(registry.checkAssignable(
                new GdArrayType(GdIntType.INT),
                new GdArrayType(GdVariantType.VARIANT)
        ));
        assertTrue(registry.checkAssignable(
                new GdArrayType(new GdObjectType("Node3D")),
                new GdArrayType(new GdObjectType("Node"))
        ));
        assertTrue(registry.checkAssignable(
                new GdDictionaryType(GdStringNameType.STRING_NAME, GdIntType.INT),
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)
        ));
        assertTrue(registry.checkAssignable(
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node3D")),
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"))
        ));
    }

    @Test
    void checkAssignableRejectsNonCovariantContainerMismatch() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        assertFalse(registry.checkAssignable(
                new GdArrayType(GdIntType.INT),
                new GdArrayType(GdFloatType.FLOAT)
        ));
        assertFalse(registry.checkAssignable(
                new GdDictionaryType(GdIntType.INT, GdIntType.INT),
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT)
        ));
        assertFalse(registry.checkAssignable(
                new GdDictionaryType(GdStringNameType.STRING_NAME, GdFloatType.FLOAT),
                new GdDictionaryType(GdStringNameType.STRING_NAME, GdIntType.INT)
        ));
    }

    @Test
    void findDefaultValues() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var registry = new ClassRegistry(api);
        var defaultValueSet = new HashSet<String>();
        for (var gdClass : registry.getExtensionGdClassList()) {
            for (var method : gdClass.methods()) {
                for (var arg : method.arguments()) {
                    if (arg.defaultValue() != null) {
                        defaultValueSet.add(arg.defaultValue());
                    }
                }
            }
        }
        IO.println("Found " + defaultValueSet.size() + " unique default values across all API methods");
        IO.println(defaultValueSet);
    }

    @Test
    void utilitySignatureShouldNormalizeTypedarrayMetadataThroughRegistry() throws IOException {
        var registry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionUtilityFunction(
                                "typedarray_default_utility",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument(
                                                "specialization_constants",
                                                "typedarray::RDPipelineSpecializationConstant",
                                                "Array[RDPipelineSpecializationConstant]([])",
                                                null
                                        )
                                )
                        )
                ),
                List.of(),
                List.of(
                        new ExtensionGdClass(
                                "RDPipelineSpecializationConstant",
                                false,
                                true,
                                "RefCounted",
                                "servers",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                List.of(),
                List.of()
        ));

        var signature = registry.findUtilityFunctionSignature("typedarray_default_utility");
        assertNotNull(signature);
        assertEquals(1, signature.parameterCount());
        assertEquals(
                new GdArrayType(new GdObjectType("RDPipelineSpecializationConstant")),
                signature.parameters().getFirst().type()
        );
        assertEquals(
                "Array[RDPipelineSpecializationConstant]([])",
                signature.parameters().getFirst().defaultValue()
        );
    }
}
