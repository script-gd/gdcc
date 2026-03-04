package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.backend.c.gen.insn.IndexLoadInsnGen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.VariantGetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantGetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexLoadInsnGenTest {
    @Test
    @DisplayName("variant_get with Variant self/key/result should avoid operand pack and use variant copy back")
    void variantGetVariantKeyVariantResult() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get(&$self, &$key"), body);
        assertTrue(body.contains("$result = godot_new_Variant_with_Variant(&__gdcc_tmp_idx_ret_"), body);
        assertFalse(body.contains("__gdcc_tmp_idx_self_variant_"), body);
        assertFalse(body.contains("__gdcc_tmp_idx_key_variant_"), body);
    }

    @Test
    @DisplayName("variant_get should pack non-Variant self operand")
    void variantGetNonVariantSelfPack() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Array(&$self)"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_"), body);
    }

    @Test
    @DisplayName("variant_get should pack non-Variant key operand")
    void variantGetNonVariantKeyPack() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_int($key)"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_key_variant_"), body);
    }

    @Test
    @DisplayName("variant_get should unpack to non-Variant result type")
    void variantGetNonVariantResultUnpack() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("$result = godot_new_int_with_Variant(&__gdcc_tmp_idx_ret_"), body);
    }

    @Test
    @DisplayName("variant_get should fail-fast when resultId is missing")
    void variantGetMissingResultFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantGetInsn(null, "self", "key"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("key", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("missing required result variable ID"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_get should fail-fast when result variable is ref")
    void variantGetRefResultFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantGetInsn("result", "self", "key"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("key", GdVariantType.VARIANT, false),
                                new VariableSpec("result", GdVariantType.VARIANT, true)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_get should fail-fast when self operand does not exist")
    void variantGetMissingVariantOperandFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantGetInsn("result", "missing_self", "key"),
                        List.of(
                                new VariableSpec("key", GdVariantType.VARIANT, false),
                                new VariableSpec("result", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("self operand variable ID 'missing_self'"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_get should fail-fast when key operand does not exist")
    void variantGetMissingKeyOperandFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantGetInsn("result", "self", "missing_key"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("result", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("key operand variable ID 'missing_key'"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_get_keyed should emit godot_variant_get_keyed call")
    void variantGetKeyedBasic() {
        var body = generateBody(
                new VariantGetKeyedInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get_keyed(&$self, &$key"), body);
    }

    @Test
    @DisplayName("variant_get_keyed should support ref key variable without extra address-of")
    void variantGetKeyedRefKeyVar() {
        var body = generateBody(
                new VariantGetKeyedInsn("result", "self", "key_ref"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key_ref", GdVariantType.VARIANT, true),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get_keyed(&$self, $key_ref"), body);
        assertFalse(body.contains("__gdcc_tmp_idx_key_variant_"), body);
    }

    @Test
    @DisplayName("variant_get_named should emit StringName variable call")
    void variantGetNamedBasic() {
        var body = generateBody(
                new VariantGetNamedInsn("result", "self", "name"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("name", GdStringNameType.STRING_NAME, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get_named(&$self, &$name"), body);
        assertFalse(body.contains("GD_STATIC_SN("), body);
    }

    @Test
    @DisplayName("variant_get_named should support ref StringName variable without extra address-of")
    void variantGetNamedRefNameVar() {
        var body = generateBody(
                new VariantGetNamedInsn("result", "self", "name_ref"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("name_ref", GdStringNameType.STRING_NAME, true),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get_named(&$self, $name_ref"), body);
    }

    @Test
    @DisplayName("variant_get_named should pack non-Variant self and unpack non-Variant result")
    void variantGetNamedNonVariantSelf() {
        var body = generateBody(
                new VariantGetNamedInsn("result", "self", "name"),
                List.of(
                        new VariableSpec("self", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), false),
                        new VariableSpec("name", GdStringNameType.STRING_NAME, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Dictionary(&$self)"), body);
        assertTrue(body.contains("$result = godot_new_int_with_Variant(&__gdcc_tmp_idx_ret_"), body);
    }

    @Test
    @DisplayName("variant_get_named should fail-fast when name operand is not StringName")
    void variantGetNamedNonStringNameFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantGetNamedInsn("result", "self", "name"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("name", GdVariantType.VARIANT, false),
                                new VariableSpec("result", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("must be StringName"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_get_indexed should emit indexed call and oob check")
    void variantGetIndexedBasic() {
        var body = generateBody(
                new VariantGetIndexedInsn("result", "self", "idx"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get_indexed(&$self, (GDExtensionInt)$idx"), body);
        assertTrue(body.contains("if (__gdcc_tmp_idx_oob_"), body);
    }

    @Test
    @DisplayName("variant_get_indexed should accept ref int index")
    void variantGetIndexedRefIntIndex() {
        var body = generateBody(
                new VariantGetIndexedInsn("result", "self", "idx_ref"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx_ref", GdIntType.INT, true),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_get_indexed(&$self, (GDExtensionInt)$idx_ref"), body);
    }

    @Test
    @DisplayName("variant_get_indexed should pack non-Variant self and unpack non-Variant result")
    void variantGetIndexedNonVariantSelf() {
        var body = generateBody(
                new VariantGetIndexedInsn("result", "self", "idx"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Array(&$self)"), body);
        assertTrue(body.contains("$result = godot_new_int_with_Variant(&__gdcc_tmp_idx_ret_"), body);
    }

    @Test
    @DisplayName("variant_get should emit r_valid check and runtime error branch")
    void variantGetEmitsValidCheck() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("if (!__gdcc_tmp_idx_valid_"), body);
        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR(\"variant_get failed: self=$self, key=$key, result=$result\""), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("variant_get invalid branch should destroy temps in reverse init order")
    void variantGetInvalidBranchDestroyOrder() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertInOrder(
                body,
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_get failed: self=$self, key=$key, result=$result\"",
                "godot_Variant_destroy(&__gdcc_tmp_idx_ret_",
                "godot_Variant_destroy(&__gdcc_tmp_idx_key_variant_",
                "godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_",
                "goto __finally__;"
        );
    }

    @Test
    @DisplayName("variant_get_indexed should emit r_oob check and runtime error branch")
    void variantGetIndexedEmitsOobCheck() {
        var body = generateBody(
                new VariantGetIndexedInsn("result", "self", "idx"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("if (__gdcc_tmp_idx_oob_"), body);
        assertTrue(body.contains("variant_get_indexed index out of bounds: index=$idx"), body);
    }

    @Test
    @DisplayName("variant_get_indexed oob branch should destroy return then self temp")
    void variantGetIndexedOobDestroyOrder() {
        var body = generateBody(
                new VariantGetIndexedInsn("result", "self", "idx"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertInOrder(
                body,
                "variant_get_indexed index out of bounds: index=$idx",
                "godot_Variant_destroy(&__gdcc_tmp_idx_ret_",
                "godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_",
                "goto __finally__;"
        );
    }

    @Test
    @DisplayName("variant_get_indexed should fail-fast when index operand is not int")
    void variantGetIndexedNonIntIndexFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantGetIndexedInsn("result", "self", "idx"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("idx", GdVariantType.VARIANT, false),
                                new VariableSpec("result", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("must be int"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_get should destroy temporary packed operands")
    void variantGetDestroysTempPackVars() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_key_variant_"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_"), body);
    }

    @Test
    @DisplayName("variant_get should destroy temporary return variant")
    void variantGetDestroysRetVariant() {
        var body = generateBody(
                new VariantGetInsn("result", "self", "key"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_ret_"), body);
    }

    private @NotNull String generateBody(@NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs) {
        return generateBody(instruction, variableSpecs, GdVoidType.VOID);
    }

    private @NotNull String generateBody(@NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs,
                                         @NotNull GdType returnType) {
        var workerClass = newTestClass();
        var func = newFunction("index_load_test", returnType);
        for (var variableSpec : variableSpecs) {
            if (variableSpec.ref()) {
                func.createAndAddRefVariable(variableSpec.id(), variableSpec.type());
            } else {
                func.createAndAddVariable(variableSpec.id(), variableSpec.type());
            }
        }

        var entry = entry(func);
        entry.instructions().add(instruction);
        workerClass.addFunction(func);

        var bodyBuilder = newBodyBuilder(workerClass, func);
        bodyBuilder.beginBasicBlock(entry.id());
        bodyBuilder.setCurrentPosition(entry, 0, instruction);
        new IndexLoadInsnGen().generateCCode(bodyBuilder);
        return bodyBuilder.build();
    }

    private @NotNull LirClassDef newTestClass() {
        return new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private @NotNull LirFunctionDef newFunction(@NotNull String name, @NotNull GdType returnType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        return func;
    }

    private @NotNull LirBasicBlock entry(@NotNull LirFunctionDef func) {
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return entry;
    }

    private @NotNull CBodyBuilder newBodyBuilder(@NotNull LirClassDef clazz, @NotNull LirFunctionDef func) {
        var classRegistry = new ClassRegistry(emptyApi());
        classRegistry.addGdccClass(clazz);
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var helper = new CGenHelper(ctx, List.of(clazz));
        return new CBodyBuilder(helper, clazz, func);
    }

    private void assertInOrder(@NotNull String body, @NotNull String... fragments) {
        var from = -1;
        for (var fragment : fragments) {
            var idx = body.indexOf(fragment, from + 1);
            assertTrue(idx >= 0, "Missing fragment: " + fragment + "\n" + body);
            from = idx;
        }
    }

    private @NotNull ExtensionAPI emptyApi() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record VariableSpec(@NotNull String id, @NotNull GdType type, boolean ref) {
    }
}
