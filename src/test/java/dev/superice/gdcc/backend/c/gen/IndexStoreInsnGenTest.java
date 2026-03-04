package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.backend.c.gen.insn.IndexStoreInsnGen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.lir.insn.VariantSetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
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

class IndexStoreInsnGenTest {
    @Test
    @DisplayName("variant_set with Variant self/key/value should avoid operand pack")
    void variantSetVariantKeyVariantValue() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_set(&$self, &$key, &$value"), body);
        assertFalse(body.contains("__gdcc_tmp_idx_self_variant_"), body);
        assertFalse(body.contains("__gdcc_tmp_idx_key_variant_"), body);
        assertFalse(body.contains("__gdcc_tmp_idx_val_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should pack non-Variant key operand")
    void variantSetNonVariantKeyPack() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_int($key)"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_key_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should pack ref int key operand")
    void variantSetRefIntKeyPack() {
        var body = generateBody(
                new VariantSetInsn("self", "key_ref", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key_ref", GdIntType.INT, true),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_int($key_ref)"), body);
        assertTrue(body.contains("godot_variant_set(&$self, &__gdcc_tmp_idx_key_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should pack non-Variant value operand")
    void variantSetNonVariantValuePack() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_int($value)"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_val_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should pack ref String value operand")
    void variantSetRefStringValuePack() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value_ref"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value_ref", GdStringType.STRING, true)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_String($value_ref)"), body);
        assertTrue(body.contains("godot_variant_set(&$self, &$key, &__gdcc_tmp_idx_val_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should allow Array self with pack and without writeback")
    void variantSetArraySelfPackSucceeds() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Array(&$self)"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($key)"), body);
        assertFalse(body.contains("$self = godot_new_Array_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set should allow ref Array self without writeback")
    void variantSetRefArraySelfPackSucceedsWithoutWriteback() {
        var body = generateBody(
                new VariantSetInsn("self_ref", "key", "value"),
                List.of(
                        new VariableSpec("self_ref", new GdArrayType(GdVariantType.VARIANT), true),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Array($self_ref)"), body);
        assertFalse(body.contains("$self_ref = godot_new_Array_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set should allow ref Dictionary self without writeback")
    void variantSetRefDictionarySelfPackSucceedsWithoutWriteback() {
        var body = generateBody(
                new VariantSetInsn("self_ref", "key", "value"),
                List.of(
                        new VariableSpec("self_ref", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), true),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Dictionary($self_ref)"), body);
        assertFalse(body.contains("$self_ref = godot_new_Dictionary_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set should emit writeback for value-semantic self")
    void variantSetValueSemanticSelfEmitsWriteback() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdFloatVectorType.VECTOR3, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Vector3(&$self)"), body);
        assertTrue(body.contains("$self = godot_new_Vector3_with_Variant(&__gdcc_tmp_idx_self_variant_"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should fail-fast when ref self requires writeback")
    void variantSetRefValueSemanticSelfFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetInsn("self_ref", "key", "value"),
                        List.of(
                                new VariableSpec("self_ref", GdStringType.STRING, true),
                                new VariableSpec("key", GdVariantType.VARIANT, false),
                                new VariableSpec("value", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("requires writeback"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set should fail-fast on unsupported self type")
    void variantSetUnsupportedSelfFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetInsn("self", "key", "value"),
                        List.of(
                                new VariableSpec("self", GdIntType.INT, false),
                                new VariableSpec("key", GdVariantType.VARIANT, false),
                                new VariableSpec("value", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("unsupported type"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set should fail-fast when self operand does not exist")
    void variantSetMissingVariantOperandFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetInsn("missing_self", "key", "value"),
                        List.of(
                                new VariableSpec("key", GdVariantType.VARIANT, false),
                                new VariableSpec("value", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("self operand variable ID 'missing_self'"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set should fail-fast when key operand does not exist")
    void variantSetMissingKeyOperandFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetInsn("self", "missing_key", "value"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("value", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("key operand variable ID 'missing_key'"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set should fail-fast when value operand does not exist")
    void variantSetMissingValueOperandFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetInsn("self", "key", "missing_value"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("key", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("value operand variable ID 'missing_value'"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set_keyed should emit godot_variant_set_keyed call")
    void variantSetKeyedBasic() {
        var body = generateBody(
                new VariantSetKeyedInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_set_keyed(&$self, &$key, &$value"), body);
    }

    @Test
    @DisplayName("variant_set_keyed should allow ref Dictionary self without writeback")
    void variantSetKeyedRefDictionarySelfPackSucceedsWithoutWriteback() {
        var body = generateBody(
                new VariantSetKeyedInsn("self_ref", "key", "value"),
                List.of(
                        new VariableSpec("self_ref", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), true),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Dictionary($self_ref)"), body);
        assertFalse(body.contains("$self_ref = godot_new_Dictionary_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set_named should emit StringName variable call")
    void variantSetNamedBasic() {
        var body = generateBody(
                new VariantSetNamedInsn("self", "name", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("name", GdStringNameType.STRING_NAME, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_set_named(&$self, &$name, &$value"), body);
        assertFalse(body.contains("GD_STATIC_SN("), body);
    }

    @Test
    @DisplayName("variant_set_named should pack non-Variant value operand")
    void variantSetNamedNonVariantValue() {
        var body = generateBody(
                new VariantSetNamedInsn("self", "name", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("name", GdStringNameType.STRING_NAME, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_int($value)"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_val_variant_"), body);
    }

    @Test
    @DisplayName("variant_set_named should fail-fast when name operand is not StringName")
    void variantSetNamedNonStringNameFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetNamedInsn("self", "name", "value"),
                        List.of(
                                new VariableSpec("self", GdVariantType.VARIANT, false),
                                new VariableSpec("name", GdVariantType.VARIANT, false),
                                new VariableSpec("value", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("must be StringName"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set_named should fail-fast when self does not support named-set")
    void variantSetNamedUnsupportedSelfFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetNamedInsn("self", "name", "value"),
                        List.of(
                                new VariableSpec("self", GdIntType.INT, false),
                                new VariableSpec("name", GdStringNameType.STRING_NAME, false),
                                new VariableSpec("value", GdVariantType.VARIANT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("unsupported type"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set_indexed should emit indexed call and oob check")
    void variantSetIndexedBasic() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_set_indexed(&$self, (GDExtensionInt)$idx, &$value"), body);
        assertTrue(body.contains("if (__gdcc_tmp_idx_oob_"), body);
    }

    @Test
    @DisplayName("variant_set_indexed should accept ref int index")
    void variantSetIndexedRefIntIndex() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx_ref", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx_ref", GdIntType.INT, true),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_set_indexed(&$self, (GDExtensionInt)$idx_ref, &$value"), body);
    }

    @Test
    @DisplayName("variant_set_indexed should pack non-Variant value operand")
    void variantSetIndexedNonVariantValue() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_int($value)"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_val_variant_"), body);
    }

    @Test
    @DisplayName("variant_set_indexed should allow Array self with pack and without writeback")
    void variantSetIndexedArraySelfPackSucceeds() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Array(&$self)"), body);
        assertFalse(body.contains("$self = godot_new_Array_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set_indexed should allow ref Array self with pack and without writeback")
    void variantSetIndexedRefArraySelfPackSucceeds() {
        var body = generateBody(
                new VariantSetIndexedInsn("self_ref", "idx", "value"),
                List.of(
                        new VariableSpec("self_ref", new GdArrayType(GdVariantType.VARIANT), true),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Array($self_ref)"), body);
        assertFalse(body.contains("$self_ref = godot_new_Array_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set_indexed should allow Dictionary self with pack and without writeback")
    void variantSetIndexedDictionarySelfPackSucceeds() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_Dictionary(&$self)"), body);
        assertFalse(body.contains("$self = godot_new_Dictionary_with_Variant("), body);
    }

    @Test
    @DisplayName("variant_set_indexed should write back PackedInt32Array self")
    void variantSetIndexedPackedInt32ArraySelfWritesBack() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", GdPackedNumericArrayType.PACKED_INT32_ARRAY, false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_new_Variant_with_PackedInt32Array(&$self)"), body);
        assertTrue(body.contains("$self = godot_new_PackedInt32Array_with_Variant(&__gdcc_tmp_idx_self_variant_"), body);
    }

    @Test
    @DisplayName("variant_set_indexed should fail-fast when ref PackedInt32Array self requires writeback")
    void variantSetIndexedRefPackedInt32ArraySelfFails() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        new VariantSetIndexedInsn("self_ref", "idx", "value"),
                        List.of(
                                new VariableSpec("self_ref", GdPackedNumericArrayType.PACKED_INT32_ARRAY, true),
                                new VariableSpec("idx", GdIntType.INT, false),
                                new VariableSpec("value", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("requires writeback"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_set should emit r_valid check and runtime error branch")
    void variantSetEmitsValidCheck() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("if (!__gdcc_tmp_idx_valid_"), body);
        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR(\"variant_set failed: self=$self, key=$key, value=$value\""), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("variant_set_indexed should emit r_oob check and runtime error branch")
    void variantSetIndexedEmitsOobCheck() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("if (__gdcc_tmp_idx_oob_"), body);
        assertTrue(body.contains("variant_set_indexed index out of bounds: index=$idx"), body);
    }

    @Test
    @DisplayName("variant_set invalid branch should destroy temps in reverse init order")
    void variantSetInvalidBranchDestroyOrder() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertInOrder(
                body,
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_set failed: self=$self, key=$key, value=$value\"",
                "godot_Variant_destroy(&__gdcc_tmp_idx_val_variant_",
                "godot_Variant_destroy(&__gdcc_tmp_idx_key_variant_",
                "godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_",
                "goto __finally__;"
        );
    }

    @Test
    @DisplayName("variant_set_indexed oob branch should destroy value then self temp")
    void variantSetIndexedOobDestroyOrder() {
        var body = generateBody(
                new VariantSetIndexedInsn("self", "idx", "value"),
                List.of(
                        new VariableSpec("self", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("idx", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertInOrder(
                body,
                "variant_set_indexed index out of bounds: index=$idx",
                "godot_Variant_destroy(&__gdcc_tmp_idx_val_variant_",
                "godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_",
                "goto __finally__;"
        );
    }

    @Test
    @DisplayName("variant_set should destroy temporary packed key and value variants")
    void variantSetDestroysTempPackVars() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdVariantType.VARIANT, false),
                        new VariableSpec("key", GdIntType.INT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_key_variant_"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_val_variant_"), body);
    }

    @Test
    @DisplayName("variant_set should destroy self temp in value-semantic writeback path")
    void variantSetValueSemanticWritebackPathDestroysSelfTemp() {
        var body = generateBody(
                new VariantSetInsn("self", "key", "value"),
                List.of(
                        new VariableSpec("self", GdFloatVectorType.VECTOR3, false),
                        new VariableSpec("key", GdVariantType.VARIANT, false),
                        new VariableSpec("value", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("$self = godot_new_Vector3_with_Variant(&__gdcc_tmp_idx_self_variant_"), body);
        assertTrue(body.contains("godot_Variant_destroy(&__gdcc_tmp_idx_self_variant_"), body);
    }

    private @NotNull String generateBody(@NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs) {
        return generateBody(instruction, variableSpecs, GdVoidType.VOID);
    }

    private @NotNull String generateBody(@NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs,
                                         @NotNull GdType returnType) {
        var workerClass = newTestClass();
        var func = newFunction("index_store_test", returnType);
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
        new IndexStoreInsnGen().generateCCode(bodyBuilder);
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
