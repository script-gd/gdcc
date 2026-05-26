package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotUtilityGeneratorTest {
    @Test
    void generateUtilitySupportShouldWriteStableStage2Files(@TempDir Path tempDir) throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        GodotUtilityGenerator.generateUtilitySupport(api, tempDir);
        var firstSnapshot = snapshot(tempDir);
        GodotUtilityGenerator.generateUtilitySupport(api, tempDir);
        var secondSnapshot = snapshot(tempDir);

        assertEquals(firstSnapshot, secondSnapshot);
        assertEquals(
                Map.of(
                        "godot_utility.h", firstSnapshot.get("godot_utility.h"),
                        "godot_utility.c", firstSnapshot.get("godot_utility.c")
                ),
                firstSnapshot
        );
    }

    @Test
    void renderUtilitySupportShouldFollowStage2HashAndVarargContract() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var files = GodotUtilityGenerator.renderUtilitySupport(api);
        var header = files.get("godot_utility.h");
        var source = files.get("godot_utility.c");

        assertAll(
                () -> assertEquals(2, files.size()),
                () -> assertEquals(api.utilityFunctions().size(), GodotUtilityGenerator.collectSymbols(api).size()),
                () -> assertTrue(header.contains("#include <godot_builtin.h>")),
                () -> assertTrue(Pattern.compile(
                        "void godot_print\\(const godot_Variant \\*\\s*arg1, "
                                + "const godot_Variant \\*\\*argv, godot_int argc\\)"
                ).matcher(header).find()),
                () -> assertTrue(header.contains("godot_float godot_deg_to_rad(godot_float deg);")),
                () -> assertTrue(source.contains("GDCC_RESOLVE_UTILITY_CACHE(gdcc_utility_print, "
                        + "\"print\", 2648703342LL, \"godot_print\", return);")),
                () -> assertTrue(source.contains(
                        "GDExtensionPtrUtilityFunction resolved = godot_variant_get_ptr_utility_function("
                                + "&utility_name, hash_value);"
                )),
                () -> assertTrue(source.contains("GDCC_BINDING_LOOKUP_FAIL_HASH(\"utility\"")),
                () -> assertTrue(source.contains(".kind = kind_value")),
                () -> assertTrue(source.contains(".function_name = function_value")),
                () -> assertTrue(source.contains(".has_primary_hash = true")),
                () -> assertTrue(source.contains(".primary_hash = hash_value")),
                () -> assertTrue(source.contains("GDExtensionConstTypePtr args[1 + argc];")),
                () -> assertFalse(source.contains("const GDExtensionConstTypePtr args[1 + argc];")),
                () -> assertFalse(functionBody(source, "void godot_print(").contains("compatibility_hashes")),
                () -> assertFalse(source.contains("hash_compatibility")),
                () -> assertUtilityLookupUsesResolveMacro(source, "godot_print", "gdcc_utility_print")
        );
    }

    @Test
    void renderUtilityPtrcallResultCarriersShouldBeInitialized() throws IOException {
        var source = GodotUtilityGenerator.renderUtilitySupport(ExtensionApiLoader.loadDefault())
                .get("godot_utility.c");

        var sinBody = functionBody(source, "godot_float godot_sin(");
        var lerpBody = functionBody(source, "godot_Variant godot_lerp(");
        var strBody = functionBody(source, "godot_String godot_str(");
        var instanceFromIdBody = functionBody(source, "godot_Object * godot_instance_from_id(");
        var printBody = functionBody(source, "void godot_print(");

        assertAll(
                () -> assertInitializedResultCarrier(sinBody, "godot_float"),
                () -> assertInitializedResultCarrier(lerpBody, "godot_Variant"),
                () -> assertInitializedResultCarrier(strBody, "godot_String"),
                () -> assertInitializedResultCarrier(instanceFromIdBody, "godot_Object *"),
                () -> assertTrue(lerpBody.contains(
                        "GDCC_RESOLVE_UTILITY_CACHE(gdcc_utility_lerp, \"lerp\", "
                                + "3389874542LL, \"godot_lerp\", return godot_new_Variant_nil());"
                )),
                () -> assertTrue(strBody.contains(
                        "GDCC_RESOLVE_UTILITY_CACHE(gdcc_utility_str, \"str\", "
                                + "32569176LL, \"godot_str\", return (godot_String){ 0 });"
                )),
                () -> assertFalse(source.contains("godot_Variant result;\n"), source),
                () -> assertFalse(source.contains("godot_String result;\n"), source),
                () -> assertFalse(printBody.contains(" result"), printBody),
                () -> assertTrue(printBody.contains("gdcc_utility_print(NULL, args, (int)(1 + argc));"),
                        printBody)
        );
    }

    @Test
    void bindingToolShouldRejectUnsupportedUtilityGenerationVersion() {
        var failure = assertThrows(IllegalArgumentException.class, () -> GodotBindingTool.run(new String[]{
                "generate-utility",
                "--gde",
                "4.4.0",
                "--out",
                "/tmp/gdcc-unsupported-utility"
        }));

        assertTrue(failure.getMessage().contains("Unsupported Godot version for utility generation: 4.4.0"));
    }

    private static void assertUtilityLookupUsesResolveMacro(
            @NotNull String source,
            @NotNull String functionName,
            @NotNull String cacheName
    ) {
        var functionStart = source.indexOf("void " + functionName + "(");
        assertTrue(functionStart >= 0, "missing generated function: " + functionName);
        var resolveMacro = source.indexOf("GDCC_RESOLVE_UTILITY_CACHE(" + cacheName, functionStart);
        var cachedCall = source.indexOf(cacheName + "(", resolveMacro);
        var macroDefinition = source.indexOf("#define GDCC_RESOLVE_UTILITY_CACHE(");
        var missCheck = source.indexOf("if (resolved == NULL)", macroDefinition);
        var cacheAssignment = source.indexOf("(cache) = resolved;", macroDefinition);

        assertAll(
                () -> assertTrue(resolveMacro > functionStart, "missing generated resolve macro"),
                () -> assertTrue(missCheck > macroDefinition, "macro must check resolved pointer before cache assignment"),
                () -> assertTrue(cacheAssignment > missCheck, "cache assignment must follow NULL check"),
                () -> assertTrue(cachedCall > resolveMacro, "cached pointer must be resolved before call")
        );
    }

    private static void assertInitializedResultCarrier(
            @NotNull String body,
            @NotNull String cType
    ) {
        assertAll(
                () -> assertTrue(body.contains(cType + " result = { 0 };"), body),
                () -> assertTrue(body.contains("(GDExtensionTypePtr)&result"), body),
                () -> assertTrue(body.contains("return result;"), body),
                () -> assertFalse(body.contains(cType + " result;\n"), body),
                () -> assertFalse(body.contains("GDExtensionUninitialized"), body)
        );
    }

    private static @NotNull String functionBody(@NotNull String source, @NotNull String functionSignature) {
        var functionStart = source.indexOf(functionSignature);
        assertTrue(functionStart >= 0, "missing generated function: " + functionSignature);
        var functionEnd = source.indexOf("\n}\n\n", functionStart);
        assertTrue(functionEnd > functionStart, "missing generated function end: " + functionSignature);
        return source.substring(functionStart, functionEnd);
    }

    private static @NotNull Map<String, String> snapshot(@NotNull Path dir) throws IOException {
        var files = new LinkedHashMap<String, String>();
        try (var stream = Files.list(dir)) {
            for (var path : stream.sorted().toList()) {
                files.put(path.getFileName().toString(), Files.readString(path));
            }
        }
        return files;
    }
}
