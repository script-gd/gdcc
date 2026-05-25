package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotBuiltinGeneratorTest {
    @Test
    void generateBuiltinSupportShouldWriteStableStage2Files(@TempDir Path tempDir) throws IOException {
        var api = ExtensionApiLoader.loadDefault();

        GodotBuiltinGenerator.generateBuiltinSupport(api, tempDir);
        var firstSnapshot = snapshot(tempDir);
        GodotBuiltinGenerator.generateBuiltinSupport(api, tempDir);
        var secondSnapshot = snapshot(tempDir);

        assertEquals(firstSnapshot, secondSnapshot);
        assertEquals(
                Map.of(
                        "godot_builtin.h", firstSnapshot.get("godot_builtin.h"),
                        "godot_builtin.c", firstSnapshot.get("godot_builtin.c")
                ),
                firstSnapshot
        );
    }

    @Test
    void renderBuiltinSupportShouldFollowStage2Contract() throws IOException {
        var files = GodotBuiltinGenerator.renderBuiltinSupport(ExtensionApiLoader.loadDefault());
        var header = files.get("godot_builtin.h");
        var source = files.get("godot_builtin.c");

        assertAll(
                () -> assertEquals(2, files.size()),
                () -> assertTrue(header.contains("#include <godot_interface.h>")),
                () -> assertTrue(header.contains("godot_new_Variant_nil(void);")),
                () -> assertTrue(header.contains("godot_new_Variant_with_Variant(const godot_Variant *value);")),
                () -> assertTrue(header.contains("godot_new_Vector3_with_float_float_float(")),
                () -> assertTrue(header.contains("godot_new_StringName_with_utf8_chars(const char *p_contents);")),
                () -> assertTrue(header.contains("godot_Array_indexed_get(const godot_Array *self, godot_int index);")),
                () -> assertTrue(header.contains("godot_Dictionary_keyed_get(")),
                () -> assertTrue(header.contains("godot_Array_get_typed_builtin(const godot_Array *self);")),
                () -> assertTrue(header.contains("godot_Dictionary_get_typed_key_builtin(")),
                () -> assertEquals(1, countOccurrences(header, "godot_Transform2D_get_origin(")),
                () -> assertFalse(header.contains("godot_new_int(")),
                () -> assertFalse(header.contains("godot_new_float(")),
                () -> assertFalse(header.contains("godot_int_op_add_int")),
                () -> assertFalse(header.contains("godot_float_op_add_float")),
                () -> assertTrue(source.contains("gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){")),
                () -> assertEquals(1, countOccurrences(source, "#define GDCC_DEFINE_VARIANT_TO_TYPE(")),
                () -> assertTrue(source.contains("GDCC_DEFINE_VARIANT_TO_TYPE(Vector3")),
                () -> assertTrue(source.contains("GDCC_DEFINE_VARIANT_FROM_TYPE_PTR(String")),
                () -> assertTrue(source.contains("GDCC_DEFINE_VARIANT_FROM_OBJECT(Object")),
                () -> assertTrue(source.contains("GDCC_RESOLVE_BUILTIN_NAMED_CACHE(")),
                () -> assertEquals(1, countOccurrences(source, "#define GDCC_RESOLVE_BUILTIN_METHOD_CACHE(")),
                () -> assertEquals(1, countOccurrences(source, "#define GDCC_BUILTIN_METHOD_ARGS(")),
                () -> assertEquals(2, countOccurrences(source, "godot_variant_get_ptr_builtin_method(")),
                () -> assertTrue(source.contains(".kind = \"builtin\"")),
                () -> assertTrue(source.contains(".function_name = function_value")),
                () -> assertTrue(source.contains(".has_primary_hash = true")),
                () -> assertTrue(source.contains("GDExtensionUninitializedVariantPtr")),
                () -> assertTrue(source.contains("GDExtensionUninitializedTypePtr")),
                () -> assertTrue(source.contains("godot_string_name_new_with_utf8_chars("
                        + "(GDExtensionUninitializedStringNamePtr)&method_name, lookup_value)")),
                () -> assertTrue(source.contains("godot_string_new_with_utf8_chars("
                        + "(GDExtensionUninitializedStringPtr)&self, p_contents);")),
                () -> assertFalse(source.contains("\"get_origin\"")),
                () -> assertBuiltinMethodUsesMacros(
                        source,
                        "godot_float godot_Vector3_length(",
                        "gdcc_builtin_method_Vector3_length",
                        "godot_float",
                        false,
                        false
                ),
                () -> assertBuiltinMethodUsesMacros(
                        source,
                        "godot_float godot_Vector3_distance_to(",
                        "gdcc_builtin_method_Vector3_distance_to",
                        "godot_float",
                        true,
                        false
                ),
                () -> assertBuiltinMethodUsesMacros(
                        source,
                        "void godot_PackedVector4Array_sort(",
                        "gdcc_builtin_method_PackedVector4Array_sort",
                        "void",
                        false,
                        true
                )
        );
    }

    @Test
    void renderBuiltinMethodShouldTryCompatibilityHashesBeforeFailing() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "compat_len",
                "float",
                false,
                true,
                false,
                false,
                1L,
                List.of(),
                List.of(2L, 3L),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("float")
        );
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass(
                        "Vector3",
                        false,
                        false,
                        null,
                        List.of(),
                        List.of(method),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of()
        );

        var source = GodotBuiltinGenerator.renderBuiltinSupport(api).get("godot_builtin.c");

        assertAll(
                () -> assertEquals(2, countOccurrences(source, "godot_variant_get_ptr_builtin_method(")),
                () -> assertTrue(source.contains(
                        "for (GDExtensionInt index = 0; resolved == NULL "
                                + "&& index < gdcc_compatibility_hash_count; index++)"
                )),
                () -> assertTrue(source.contains(
                        "GDCC_RESOLVE_BUILTIN_METHOD_CACHE(gdcc_builtin_method_Vector3_compat_len, "
                                + "GDEXTENSION_VARIANT_TYPE_VECTOR3, \"compat_len\", 1LL, compatibility_hashes, 2, "
                                + "\"godot_Vector3_compat_len\", \"Vector3\", return (godot_float)0);"
                )),
                () -> assertTrue(source.contains(
                        "resolved = godot_variant_get_ptr_builtin_method("
                                + "variant_type, &method_name, gdcc_compatibility_hashes[index]);"
                )),
                () -> assertTrue(source.contains(
                        "static const GDExtensionInt compatibility_hashes[] = { 2LL, 3LL };"
                )),
                () -> assertTrue(source.contains(".compatibility_hashes = gdcc_compatibility_hashes")),
                () -> assertTrue(source.contains(".compatibility_hash_count = gdcc_compatibility_hash_count")),
                () -> assertBuiltinMethodUsesMacros(
                        source,
                        "godot_float godot_Vector3_compat_len(",
                        "gdcc_builtin_method_Vector3_compat_len",
                        "godot_float",
                        false,
                        false
                )
        );
    }

    @Test
    void symbolHelperShouldRejectSameCNameWithDifferentSignature() {
        var left = symbol(
                "godot_conflict",
                "void",
                List.of(new GodotBindingSymbol.Parameter(
                        "self",
                        "godot_String *",
                        GodotBindingSymbol.Abi.MUTABLE_TYPE_PTR
                ))
        );
        var right = symbol(
                "godot_conflict",
                "void",
                List.of(new GodotBindingSymbol.Parameter(
                        "self",
                        "const godot_String *",
                        GodotBindingSymbol.Abi.CONST_TYPE_PTR
                ))
        );

        var failure = assertThrows(
                IllegalStateException.class,
                () -> GodotBindingSymbolHelper.validate(List.of(left, right))
        );

        assertAll(
                () -> assertTrue(failure.getMessage().contains("godot_conflict")),
                () -> assertTrue(failure.getMessage().contains("MUTABLE_TYPE_PTR")),
                () -> assertTrue(failure.getMessage().contains("CONST_TYPE_PTR"))
        );
    }

    private static @NotNull GodotBindingSymbol symbol(
            @NotNull String cFunctionName,
            @NotNull String returnType,
            @NotNull List<GodotBindingSymbol.Parameter> parameters
    ) {
        return new GodotBindingSymbol(
                GodotBindingSymbol.Family.BUILTIN,
                "String",
                "conflict",
                cFunctionName,
                returnType,
                parameters,
                false,
                null,
                List.of()
        );
    }

    private static void assertBuiltinMethodUsesMacros(
            @NotNull String source,
            @NotNull String functionSignature,
            @NotNull String cacheName,
            @NotNull String returnType,
            boolean hasArguments,
            boolean voidReturn
    ) {
        var functionStart = source.indexOf(functionSignature);
        assertTrue(functionStart >= 0, "missing generated function: " + functionSignature);
        var functionEnd = source.indexOf("\n}\n\n", functionStart);
        assertTrue(functionEnd > functionStart, "missing generated function end: " + functionSignature);
        var body = source.substring(functionStart, functionEnd);

        assertAll(
                () -> assertTrue(body.contains("GDCC_RESOLVE_BUILTIN_METHOD_CACHE(" + cacheName),
                        "missing builtin method resolve macro"),
                () -> assertEquals(hasArguments, body.contains("GDCC_BUILTIN_METHOD_ARGS(args,"),
                        "argument array macro usage mismatch"),
                () -> assertTrue(body.contains(voidReturn
                                ? "GDCC_BUILTIN_METHOD_VOID" + (hasArguments ? "(" : "0(") + cacheName
                                : "GDCC_BUILTIN_METHOD_RETURN" + (hasArguments ? "(" : "0(") + cacheName
                                  + ", self" + (hasArguments ? ", args, " : ", ") + returnType),
                        "missing builtin method call macro"),
                () -> assertFalse(body.contains("godot_variant_get_ptr_builtin_method("),
                        "method wrapper must not inline pointer lookup"),
                () -> assertFalse(body.contains("const GDExtensionConstTypePtr args[]"),
                        "method wrapper must not inline argument array declaration"),
                () -> assertFalse(body.contains(cacheName + "((GDExtensionTypePtr)self"),
                        "method wrapper must not inline ptrcall")
        );
    }

    private static int countOccurrences(@NotNull String text, @NotNull String needle) {
        var count = 0;
        var from = 0;
        while (true) {
            var index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
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
