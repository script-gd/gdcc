package gd.script.gdcc.scope;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract tests for the compiler-synthesized GDScript language function registry
/// (`len`/`char`/`ord` in the current phase). These functions are registered by Godot's
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
        assertAll(
                () -> assertNotNull(len),
                () -> assertNotNull(chr),
                () -> assertNotNull(ord),
                // Common synthetic-record fields: GDScript category and zero hash (no engine
                // utility hash exists; hash consumers never see this table).
                () -> assertEquals("GDScript", len.category()),
                () -> assertEquals("GDScript", chr.category()),
                () -> assertEquals("GDScript", ord.category()),
                () -> assertEquals(0, len.hash()),
                () -> assertEquals(0, chr.hash()),
                () -> assertEquals(0, ord.hash()),
                () -> assertFalse(len.isVararg()),
                () -> assertFalse(chr.isVararg()),
                () -> assertFalse(ord.isVararg()),
                // Raw metadata is anchored name-by-name to Godot 4.5 MethodInfo.
                () -> assertEquals("int", len.returnType()),
                () -> assertEquals("String", chr.returnType()),
                () -> assertEquals("int", ord.returnType()),
                () -> assertSingleArgument(len.arguments(), "var", "Variant"),
                () -> assertSingleArgument(chr.arguments(), "code", "int"),
                () -> assertSingleArgument(ord.arguments(), "char", "String")
        );
    }

    @Test
    void syntheticLanguageFunctionsDoNotCollideWithExtensionUtilities() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        // Anchor the premise: the API dump itself must not carry these names, otherwise the
        // synthetic table would shadow (or be shadowed by) real engine metadata.
        for (var name : List.of("len", "char", "ord")) {
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

        for (var name : List.of("len", "char", "ord")) {
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

        assertAll(
                () -> assertNotNull(lenSignature),
                () -> assertNotNull(charSignature),
                () -> assertNotNull(ordSignature),
                () -> assertEquals(GdIntType.INT, lenSignature.returnType()),
                () -> assertEquals(GdStringType.STRING, charSignature.returnType()),
                () -> assertEquals(GdIntType.INT, ordSignature.returnType()),
                () -> assertEquals(1, lenSignature.parameterCount()),
                () -> assertEquals(1, charSignature.parameterCount()),
                () -> assertEquals(1, ordSignature.parameterCount()),
                () -> assertEquals(GdVariantType.VARIANT, lenSignature.parameters().getFirst().type()),
                () -> assertEquals(GdIntType.INT, charSignature.parameters().getFirst().type()),
                () -> assertEquals(GdStringType.STRING, ordSignature.parameters().getFirst().type()),
                () -> assertFalse(lenSignature.isVararg())
        );
    }

    @Test
    void rawExtensionMetadataConsumersStayIsolatedFromSyntheticTable() throws IOException {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        for (var name : List.of("len", "char", "ord")) {
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
