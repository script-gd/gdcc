package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
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
    void renderBuiltinVarargMethodsShouldUseDynamicArgvWithoutZeroLengthVla() {
        var emit = new ExtensionBuiltinClass.ClassMethod(
                "emit",
                "void",
                true,
                true,
                false,
                false,
                1L,
                List.of(),
                List.of(),
                null
        );
        var call = new ExtensionBuiltinClass.ClassMethod(
                "call",
                "Variant",
                true,
                true,
                false,
                false,
                2L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("Variant")
        );
        var rpcId = new ExtensionBuiltinClass.ClassMethod(
                "rpc_id",
                "void",
                true,
                true,
                false,
                false,
                3L,
                List.of(new ExtensionFunctionArgument("peer_id", "int", null, null)),
                List.of(),
                null
        );
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionBuiltinClass(
                                "Signal",
                                false,
                                false,
                                null,
                                List.of(),
                                List.of(emit),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new ExtensionBuiltinClass(
                                "Callable",
                                false,
                                false,
                                null,
                                List.of(),
                                List.of(call, rpcId),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                List.of(),
                List.of(),
                List.of()
        );

        var files = GodotBuiltinGenerator.renderBuiltinSupport(api);
        var header = files.get("godot_builtin.h");
        var source = files.get("godot_builtin.c");
        var emitBody = functionBody(source, "void godot_Signal_emit(");
        var callBody = functionBody(source, "godot_Variant godot_Callable_call(");
        var rpcIdBody = functionBody(source, "void godot_Callable_rpc_id(");

        assertAll(
                () -> assertTrue(header.contains(
                        "void godot_Signal_emit(const godot_Signal *self, const godot_Variant **argv, godot_int argc);"
                )),
                () -> assertTrue(header.contains(
                        "godot_Variant godot_Callable_call(const godot_Callable *self, "
                                + "const godot_Variant **argv, godot_int argc);"
                )),
                () -> assertTrue(header.contains(
                        "void godot_Callable_rpc_id(const godot_Callable *self, godot_int peer_id, "
                                + "const godot_Variant **argv, godot_int argc);"
                )),
                () -> assertTrue(emitBody.contains("GDExtensionConstTypePtr args[0 + (argc > 0 ? argc : 1)];")),
                () -> assertTrue(emitBody.contains("(0 + argc == 0) ? NULL : args")),
                () -> assertTrue(emitBody.contains(
                        "GDCC_BUILTIN_METHOD_VOID(gdcc_builtin_method_Signal_emit, self, "
                                + "(0 + argc == 0) ? NULL : args, 0 + argc);"
                )),
                () -> assertFalse(emitBody.contains("GDCC_BUILTIN_METHOD_VOID0(")),
                () -> assertFalse(emitBody.contains("GDCC_BUILTIN_METHOD_ARGS(")),
                () -> assertFalse(emitBody.contains("args[0 + argc]")),
                () -> assertTrue(callBody.contains("GDExtensionConstTypePtr args[0 + (argc > 0 ? argc : 1)];")),
                () -> assertTrue(callBody.contains(
                        "GDCC_BUILTIN_METHOD_RETURN(gdcc_builtin_method_Callable_call, self, "
                                + "(0 + argc == 0) ? NULL : args, godot_Variant, 0 + argc);"
                )),
                () -> assertTrue(rpcIdBody.contains("GDExtensionConstTypePtr args[1 + (argc > 0 ? argc : 1)];")),
                () -> assertTrue(rpcIdBody.contains("args[0] = (GDExtensionConstTypePtr)&peer_id;")),
                () -> assertTrue(rpcIdBody.contains("args[1 + index] = (GDExtensionConstTypePtr)argv[index];")),
                () -> assertTrue(rpcIdBody.contains("(1 + argc == 0) ? NULL : args")),
                () -> assertEquals(
                        3,
                        GodotBuiltinGenerator.collectSymbols(api).stream()
                                .filter(GodotBindingSymbol::vararg)
                                .count()
                )
        );
    }

    @Test
    void renderBuiltinSupportShouldKeepCheckedInVarargWrappersInSync() throws IOException {
        var api = ExtensionApiLoader.loadDefault();
        var files = GodotBuiltinGenerator.renderBuiltinSupport(api);
        var header = files.get("godot_builtin.h").replace("\r\n", "\n");
        var source = files.get("godot_builtin.c").replace("\r\n", "\n");
        var checkedInRoot = Path.of("src/main/c/codegen/include_451/godot");
        // Checked-in files may use CRLF depending on git autocrlf; normalize before comparing.
        var checkedInHeader = Files.readString(checkedInRoot.resolve("godot_builtin.h")).replace("\r\n", "\n");
        var checkedInSource = Files.readString(checkedInRoot.resolve("godot_builtin.c")).replace("\r\n", "\n");
        var varargSymbols = GodotBuiltinGenerator.collectSymbols(api).stream()
                .filter(GodotBindingSymbol::vararg)
                .toList();
        var expectedNames = List.of(
                "godot_Callable_bind",
                "godot_Callable_call",
                "godot_Callable_call_deferred",
                "godot_Callable_rpc",
                "godot_Callable_rpc_id",
                "godot_Signal_emit"
        );

        assertEquals(
                expectedNames,
                varargSymbols.stream().map(GodotBindingSymbol::cFunctionName).sorted().toList()
        );
        for (var symbol : varargSymbols) {
            var signature = symbol.returnType() + " " + symbol.cFunctionName() + "(";
            var generatedBody = functionBody(source, signature);
            var checkedInBody = functionBody(checkedInSource, signature);
            var selfType = symbol.parameters().getFirst().cType();
            assertAll(
                    symbol.cFunctionName(),
                    () -> assertTrue(header.contains(symbol.cFunctionName() + "("), header),
                    () -> assertTrue(checkedInHeader.contains(symbol.cFunctionName() + "("), checkedInHeader),
                    () -> assertTrue(header.contains("const godot_Variant **argv"), header),
                    () -> assertTrue(checkedInHeader.contains("const godot_Variant **argv"), checkedInHeader),
                    () -> assertTrue(selfType.contains("const "), selfType),
                    () -> assertEquals(generatedBody, checkedInBody),
                    () -> assertTrue(generatedBody.contains(" + (argc > 0 ? argc : 1)];"), generatedBody),
                    () -> assertTrue(generatedBody.contains(" ? NULL : args"), generatedBody),
                    () -> assertFalse(generatedBody.contains("GDCC_BUILTIN_METHOD_ARGS("), generatedBody),
                    () -> assertFalse(generatedBody.contains("GDCC_BUILTIN_METHOD_VOID0("), generatedBody),
                    () -> assertFalse(generatedBody.contains("GDCC_BUILTIN_METHOD_RETURN0("), generatedBody)
            );
        }
    }

    @Test
    void renderBuiltinPtrcallResultCarriersShouldBeInitialized() throws IOException {
        var source = GodotBuiltinGenerator.renderBuiltinSupport(ExtensionApiLoader.loadDefault()).get("godot_builtin.c");

        var substrBody = functionBody(source, "godot_String godot_String_substr(");
        var stringAddBody = functionBody(source, "godot_String godot_String_op_add_String(");
        var vector3XBody = functionBody(source, "godot_float godot_Vector3_get_x(");
        var arrayTypedScriptBody = functionBody(source, "godot_Variant godot_Array_get_typed_script(");
        var dictionaryTypedValueScriptBody = functionBody(
                source,
                "godot_Variant godot_Dictionary_get_typed_value_script("
        );
        var arrayIndexedBody = functionBody(source, "godot_Variant godot_Array_indexed_get(");
        var dictionaryKeyedBody = functionBody(source, "godot_Variant godot_Dictionary_keyed_get(");

        assertAll(
                () -> assertTrue(source.contains(
                        "/* Builtin ptrcall return slots are assignment targets, "
                                + "not construction destinations. */ \\\n"
                )),
                () -> assertTrue(source.contains("return_type result = { 0 }; \\")),
                () -> assertFalse(source.contains("return_type result; \\")),
                () -> assertTrue(substrBody.contains(
                        "GDCC_BUILTIN_METHOD_RETURN(gdcc_builtin_method_String_substr, self, args, godot_String, 2);"
                )),
                () -> assertTrue(arrayTypedScriptBody.contains(
                        "GDCC_BUILTIN_METHOD_RETURN0(gdcc_builtin_method_Array_get_typed_script, self, godot_Variant);"
                )),
                () -> assertTrue(dictionaryTypedValueScriptBody.contains(
                        "GDCC_BUILTIN_METHOD_RETURN0("
                                + "gdcc_builtin_method_Dictionary_get_typed_value_script, self, godot_Variant);"
                )),
                () -> assertInitializedCarrier(stringAddBody, "godot_String", "result"),
                () -> assertInitializedCarrier(vector3XBody, "godot_float", "value"),
                () -> assertInitializedCarrier(arrayIndexedBody, "godot_Variant", "result"),
                () -> assertInitializedCarrier(dictionaryKeyedBody, "godot_Variant", "result")
        );
    }

    @Test
    void renderBuiltinConstructionDestinationsShouldRemainUninitializedStorage() throws IOException {
        var source = GodotBuiltinGenerator.renderBuiltinSupport(ExtensionApiLoader.loadDefault()).get("godot_builtin.c");

        var variantNilBody = functionBody(source, "godot_Variant godot_new_Variant_nil(");
        var vector3ConstructorBody = functionBody(source, "godot_Vector3 godot_new_Vector3_with_float_float_float(");
        var stringUtf8Body = functionBody(source, "godot_String godot_new_String_with_utf8_chars(");

        assertAll(
                () -> assertTrue(source.contains("GDExtensionUninitializedVariantPtr")),
                () -> assertTrue(source.contains("GDExtensionUninitializedTypePtr")),
                () -> assertTrue(source.contains("GDExtensionUninitializedStringPtr")),
                () -> assertTrue(variantNilBody.contains("godot_Variant self;")),
                () -> assertTrue(variantNilBody.contains(
                        "godot_variant_new_nil((GDExtensionUninitializedVariantPtr)&self);"
                )),
                () -> assertFalse(variantNilBody.contains("godot_Variant self = { 0 };"), variantNilBody),
                () -> assertTrue(vector3ConstructorBody.contains("godot_Vector3 self;")),
                () -> assertTrue(vector3ConstructorBody.contains(
                        "gdcc_builtin_ctor_Vector3_3((GDExtensionUninitializedTypePtr)&self, args);"
                )),
                () -> assertFalse(vector3ConstructorBody.contains("godot_Vector3 self = { 0 };"),
                        vector3ConstructorBody),
                () -> assertTrue(stringUtf8Body.contains("godot_String self;")),
                () -> assertTrue(stringUtf8Body.contains(
                        "godot_string_new_with_utf8_chars((GDExtensionUninitializedStringPtr)&self, p_contents);"
                )),
                () -> assertFalse(stringUtf8Body.contains("godot_String self = { 0 };"), stringUtf8Body)
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
        var body = functionBody(source, functionSignature);

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

    private static void assertInitializedCarrier(
            @NotNull String body,
            @NotNull String cType,
            @NotNull String name
    ) {
        assertAll(
                () -> assertTrue(body.contains(cType + " " + name + " = { 0 };"), body),
                () -> assertFalse(body.contains(cType + " " + name + ";\n"), body)
        );
    }

    private static @NotNull String functionBody(@NotNull String source, @NotNull String functionSignature) {
        var functionStart = source.indexOf(functionSignature);
        assertTrue(functionStart >= 0, "missing generated function: " + functionSignature);
        var functionEnd = source.indexOf("\n}\n\n", functionStart);
        assertTrue(functionEnd > functionStart, "missing generated function end: " + functionSignature);
        return source.substring(functionStart, functionEnd);
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
