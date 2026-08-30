package gd.script.gdcc.scope;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract tests for the compiler-synthesized GDScript language function registry
/// (`len`/`char`/`ord`/`range`/`is_instance_of`/`load`). These functions are registered by Godot's
/// GDScript module rather than the GDExtension API, so the registry synthesizes their metadata
/// and routes resolution/signature queries to the synthetic table while keeping raw
/// extension-metadata consumers (`findUtilityFunction`/`getExtensionUtilityFunctionList`)
/// isolated from it.
class ClassRegistryGdScriptLanguageFunctionTest {
    @Test
    void syntheticLanguageFunctionsAreRegisteredWithExpectedSignatures() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var len = registry.findGdScriptLanguageFunction("len");
        var chr = registry.findGdScriptLanguageFunction("char");
        var ord = registry.findGdScriptLanguageFunction("ord");
        var range = registry.findGdScriptLanguageFunction("range");
        var isInstanceOf = registry.findGdScriptLanguageFunction("is_instance_of");
        var load = registry.findGdScriptLanguageFunction("load");
        assertAll(
                () -> assertNotNull(len),
                () -> assertNotNull(chr),
                () -> assertNotNull(ord),
                () -> assertNotNull(range),
                () -> assertNotNull(isInstanceOf),
                () -> assertNotNull(load),
                // Common synthetic-record fields: GDScript category and zero hash (no engine
                // utility hash exists; hash consumers never see this table).
                () -> assertEquals("GDScript", len.category()),
                () -> assertEquals("GDScript", chr.category()),
                () -> assertEquals("GDScript", ord.category()),
                () -> assertEquals("GDScript", range.category()),
                () -> assertEquals("GDScript", isInstanceOf.category()),
                () -> assertEquals("GDScript", load.category()),
                () -> assertEquals(0, len.hash()),
                () -> assertEquals(0, chr.hash()),
                () -> assertEquals(0, ord.hash()),
                () -> assertEquals(0, range.hash()),
                () -> assertEquals(0, isInstanceOf.hash()),
                () -> assertEquals(0, load.hash()),
                () -> assertFalse(len.isVararg()),
                () -> assertFalse(chr.isVararg()),
                () -> assertFalse(ord.isVararg()),
                // `range` is vararg with zero fixed parameters, mirroring Godot's MethodInfo.
                () -> assertTrue(range.isVararg()),
                () -> assertFalse(isInstanceOf.isVararg()),
                () -> assertFalse(load.isVararg()),
                // Raw metadata is anchored name-by-name to Godot 4.5 MethodInfo.
                () -> assertEquals("int", len.returnType()),
                () -> assertEquals("String", chr.returnType()),
                () -> assertEquals("int", ord.returnType()),
                // Deliberately unparameterized `Array` (Godot MethodInfo alignment).
                () -> assertEquals("Array", range.returnType()),
                () -> assertEquals("bool", isInstanceOf.returnType()),
                () -> assertEquals("Resource", load.returnType()),
                () -> assertSingleArgument(len.arguments(), "var", "Variant"),
                () -> assertSingleArgument(chr.arguments(), "code", "int"),
                () -> assertSingleArgument(ord.arguments(), "char", "String"),
                () -> assertTrue(range.arguments().isEmpty()),
                () -> assertEquals(2, isInstanceOf.arguments().size()),
                () -> assertEquals("value", isInstanceOf.arguments().getFirst().name()),
                () -> assertEquals("Variant", isInstanceOf.arguments().getFirst().type()),
                () -> assertEquals("type", isInstanceOf.arguments().get(1).name()),
                () -> assertEquals("Variant", isInstanceOf.arguments().get(1).type()),
                () -> assertSingleArgument(load.arguments(), "path", "String")
        );
    }

    @Test
    void syntheticLanguageFunctionsDoNotCollideWithExtensionUtilities() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        // Anchor the premise: the API dump itself must not carry these names, otherwise the
        // synthetic table would shadow (or be shadowed by) real engine metadata.
        for (var name : List.of("len", "char", "ord", "range", "is_instance_of", "load")) {
            assertTrue(
                    api.utilityFunctions().stream().noneMatch(uf -> name.equals(uf.name())),
                    "extension API must not define '" + name + "'"
            );
        }
    }

    @Test
    void syntheticLanguageFunctionCollidingWithExtensionUtilityFailsFast() {
        // Resolution gives the extension table priority while backend routing keys off the
        // synthetic table; a same-name extension utility would split one name across two
        // backends, so the registry must refuse to boot on collision.
        var apiWithCollidingUtility = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionUtilityFunction(
                        "len",
                        "int",
                        "general",
                        false,
                        1,
                        List.of(new ExtensionFunctionArgument("var", "Variant", null, null))
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        var ex = assertThrows(
                IllegalStateException.class,
                () -> new ClassRegistry(apiWithCollidingUtility)
        );
        assertTrue(ex.getMessage().contains("'len'"), ex.getMessage());
    }

    @Test
    void resolutionQueriesConsultSyntheticTable() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        for (var name : List.of("len", "char", "ord", "range", "is_instance_of", "load")) {
            var resolved = registry.resolveFunctions(name);
            assertEquals(1, resolved.size(), "resolveFunctions must find synthetic '" + name + "'");
            assertEquals(name, resolved.getFirst().getName());
            assertTrue(registry.isUtilityFunction(name), "isUtilityFunction must include '" + name + "'");
            assertTrue(registry.isGdScriptLanguageFunction(name));
        }
        // Regular extension utilities are not language functions; unknown names resolve nowhere.
        assertFalse(registry.isGdScriptLanguageFunction("print"));
        assertFalse(registry.isGdScriptLanguageFunction("does_not_exist"));
        assertNull(registry.findGdScriptLanguageFunction("print"));
        assertTrue(registry.resolveFunctions("does_not_exist").isEmpty());
    }

    @Test
    void syntheticLanguageFunctionSignaturesResolveThroughSharedPipeline() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var lenSignature = registry.findUtilityFunctionSignature("len");
        var charSignature = registry.findUtilityFunctionSignature("char");
        var ordSignature = registry.findUtilityFunctionSignature("ord");
        var rangeSignature = registry.findUtilityFunctionSignature("range");
        var isInstanceOfSignature = registry.findUtilityFunctionSignature("is_instance_of");
        var loadSignature = registry.findUtilityFunctionSignature("load");

        assertAll(
                () -> assertNotNull(lenSignature),
                () -> assertNotNull(charSignature),
                () -> assertNotNull(ordSignature),
                () -> assertNotNull(rangeSignature),
                () -> assertNotNull(isInstanceOfSignature),
                () -> assertNotNull(loadSignature),
                () -> assertEquals(GdIntType.INT, lenSignature.returnType()),
                () -> assertEquals(GdStringType.STRING, charSignature.returnType()),
                () -> assertEquals(GdIntType.INT, ordSignature.returnType()),
                () -> assertEquals(GdBoolType.BOOL, isInstanceOfSignature.returnType()),
                // `load` declares the engine Resource object type; lowering rewrites the call to
                // the ResourceLoader singleton instance call, so this signature only ever feeds
                // frontend argument checking.
                () -> assertEquals(new GdObjectType("Resource"), loadSignature.returnType()),
                () -> assertEquals(1, lenSignature.parameterCount()),
                () -> assertEquals(1, charSignature.parameterCount()),
                () -> assertEquals(1, ordSignature.parameterCount()),
                () -> assertEquals(0, rangeSignature.parameterCount()),
                () -> assertEquals(2, isInstanceOfSignature.parameterCount()),
                () -> assertEquals(1, loadSignature.parameterCount()),
                () -> assertEquals(GdVariantType.VARIANT, lenSignature.parameters().getFirst().type()),
                () -> assertEquals(GdIntType.INT, charSignature.parameters().getFirst().type()),
                () -> assertEquals(GdStringType.STRING, ordSignature.parameters().getFirst().type()),
                () -> assertEquals(GdVariantType.VARIANT, isInstanceOfSignature.parameters().getFirst().type()),
                () -> assertEquals(GdVariantType.VARIANT, isInstanceOfSignature.parameters().get(1).type()),
                () -> assertEquals(GdStringType.STRING, loadSignature.parameters().getFirst().type()),
                () -> assertFalse(lenSignature.isVararg()),
                () -> assertTrue(rangeSignature.isVararg()),
                () -> assertFalse(isInstanceOfSignature.isVararg()),
                () -> assertFalse(loadSignature.isVararg()),
                // `range` returns the unparameterized (generic) Array.
                () -> assertInstanceOf(GdArrayType.class, rangeSignature.returnType()),
                () -> assertTrue(
                        assertInstanceOf(GdArrayType.class, rangeSignature.returnType()).isGenericArray()
                )
        );
    }

    @Test
    void rawExtensionMetadataConsumersStayIsolatedFromSyntheticTable() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        for (var name : List.of("len", "char", "ord", "range", "is_instance_of", "load")) {
            // `construct_standalone_callable` must keep failing fast on first-class references.
            assertNull(registry.findUtilityFunction(name), "findUtilityFunction must exclude '" + name + "'");
            // `godot_utility` wrapper generation must not invent `godot_len`-style wrappers.
            assertTrue(
                    registry.getExtensionUtilityFunctionList().stream().noneMatch(uf -> name.equals(uf.name())),
                    "getExtensionUtilityFunctionList must exclude '" + name + "'"
            );
        }
    }

    private static void assertSingleArgument(
            List<ExtensionFunctionArgument> arguments,
            String expectedName,
            String expectedType
    ) {
        assertEquals(1, arguments.size());
        assertEquals(expectedName, arguments.getFirst().name());
        assertEquals(expectedType, arguments.getFirst().type());
        assertNull(arguments.getFirst().defaultValue());
    }
}
