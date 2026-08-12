package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.ConstructContainerLiteralInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Codegen contract for `construct_container_literal`.
///
/// Anchors empty/generic/typed paths, pack order, Dictionary even-count, and result-type guards
/// against `frontend_container_literal_implementation.md` §8.
class CContainerLiteralInsnGenTest {

    @Test
    @DisplayName("generic empty Array emits plain constructor and no push_back")
    void genericEmptyArrayEmitsPlainCtorWithoutPushBack() {
        var body = generateFilledArray(new GdArrayType(GdVariantType.VARIANT), List.of());
        assertTrue(body.contains("godot_new_Array()"), body);
        assertFalse(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertFalse(body.contains("godot_Array_push_back"), body);
    }

    @Test
    @DisplayName("generic Array packs each scalar and push_back in source order")
    void genericArrayPacksScalarsAndPushBackInOrder() {
        var body = generateFilledArray(
                new GdArrayType(GdVariantType.VARIANT),
                List.of(GdIntType.INT, GdStringType.STRING)
        );
        assertTrue(body.contains("godot_new_Array()"), body);
        assertTrue(body.contains("godot_new_Variant_with_int"), body);
        assertTrue(body.contains("godot_new_Variant_with_String"), body);
        assertTrue(body.contains("godot_Array_push_back"), body);
        var firstPack = body.indexOf("godot_new_Variant_with_int");
        var secondPack = body.indexOf("godot_new_Variant_with_String");
        var firstPush = body.indexOf("godot_Array_push_back");
        var secondPush = body.indexOf("godot_Array_push_back", firstPush + 1);
        assertTrue(firstPack >= 0 && secondPack > firstPack, body);
        assertTrue(firstPush > firstPack && secondPush > secondPack, body);
        assertTrue(body.contains("godot_Variant_destroy"), body);
    }

    @Test
    @DisplayName("typed Array[int] uses typed empty ctor then packs int Variants")
    void typedArrayIntUsesTypedCtorAndPackedAppend() {
        var body = generateFilledArray(new GdArrayType(GdIntType.INT), List.of(GdIntType.INT, GdIntType.INT));
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        assertTrue(body.contains("godot_new_Variant_with_int"), body);
        assertTrue(body.contains("godot_Array_push_back"), body);
    }

    @Test
    @DisplayName("typed Array[float] only packs float carriers (frontend already cast int->float)")
    void typedArrayFloatOnlyPacksFloatCarriers() {
        var body = generateFilledArray(new GdArrayType(GdFloatType.FLOAT), List.of(GdFloatType.FLOAT));
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertTrue(body.contains("godot_new_Variant_with_float"), body);
        assertFalse(body.contains("godot_new_Variant_with_int"), body);
        assertTrue(body.contains("godot_Array_push_back"), body);
    }

    @Test
    @DisplayName("generic empty Dictionary emits plain constructor and no set")
    void genericEmptyDictionaryEmitsPlainCtorWithoutSet() {
        var body = generateFilledDictionary(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                List.of()
        );
        assertTrue(body.contains("godot_new_Dictionary()"), body);
        assertFalse(
                body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"),
                body
        );
        assertFalse(body.contains("godot_Dictionary_set"), body);
    }

    @Test
    @DisplayName("generic Dictionary packs key/value pairs and sets with bool failure branch")
    void genericDictionaryPacksPairsAndSetsWithFailureBranch() {
        var body = generateFilledDictionary(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                List.of(GdStringType.STRING, GdIntType.INT, GdStringType.STRING, GdIntType.INT)
        );
        assertTrue(body.contains("godot_new_Dictionary()"), body);
        assertTrue(body.contains("godot_new_Variant_with_String"), body);
        assertTrue(body.contains("godot_new_Variant_with_int"), body);
        assertTrue(body.contains("godot_Dictionary_set"), body);
        assertTrue(body.contains("if (!__gdcc_tmp_clit_dict_set_ok_"), body);
        assertTrue(body.contains("construct_container_literal dictionary set failed"), body);
        // Pack temps must be destroyed on the success path (before the failure if), not only inside it.
        var firstSet = body.indexOf("godot_Dictionary_set");
        var firstDestroy = body.indexOf("godot_Variant_destroy", firstSet);
        var firstFailIf = body.indexOf("if (!__gdcc_tmp_clit_dict_set_ok_", firstSet);
        assertTrue(firstSet >= 0 && firstDestroy > firstSet, body);
        assertTrue(firstFailIf > firstDestroy, body);
    }

    @Test
    @DisplayName("typed Dictionary uses typed empty ctor then packs target-typed carriers")
    void typedDictionaryUsesTypedCtorAndPackedSet() {
        var body = generateFilledDictionary(
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                List.of(GdStringType.STRING, GdIntType.INT)
        );
        assertTrue(
                body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"),
                body
        );
        assertTrue(body.contains("godot_Dictionary_set"), body);
        assertTrue(body.contains("godot_new_Variant_with_String"), body);
        assertTrue(body.contains("godot_new_Variant_with_int"), body);
    }

    @Test
    @DisplayName("object element packs to Variant and destroys the pack temp after push_back")
    void objectElementPacksAndDestroysTempAfterAppend() {
        var body = generateFilledArray(
                new GdArrayType(GdVariantType.VARIANT),
                List.of(new GdObjectType("Node"))
        );
        assertTrue(body.contains("godot_Array_push_back"), body);
        // Object pack uses fat_ptr_to_variant style helpers; destroy must follow push_back.
        assertTrue(body.contains("godot_Variant_destroy") || body.contains("_to_variant"), body);
        var push = body.indexOf("godot_Array_push_back");
        var destroy = body.indexOf("godot_Variant_destroy", push);
        assertTrue(push >= 0 && destroy > push, body);
    }

    @Test
    @DisplayName("nested Array element is packed as Variant after outer empty construct")
    void nestedArrayElementIsPackedAsVariant() {
        var outer = new GdArrayType(GdVariantType.VARIANT);
        var inner = new GdArrayType(GdVariantType.VARIANT);
        var body = generateFilledArray(outer, List.of(inner));
        assertTrue(body.contains("godot_new_Array()"), body);
        assertTrue(body.contains("godot_new_Variant_with_Array"), body);
        assertTrue(body.contains("godot_Array_push_back"), body);
    }

    @Test
    @DisplayName("Dictionary odd operand count is rejected")
    void dictionaryOddOperandCountIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("odd_dict");
        func.createAndAddVariable("k0", GdStringType.STRING);
        func.createAndAddVariable("dict", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT));
        entry(func).appendInstruction(new ConstructContainerLiteralInsn(
                "dict",
                List.of(new LirInstruction.VariableOperand("k0"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("even operand count"), ex.getMessage());
    }

    @Test
    @DisplayName("missing result variable ID is rejected")
    void missingResultIdIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("missing_result");
        entry(func).appendInstruction(new ConstructContainerLiteralInsn(null, List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("missing result variable ID"), ex.getMessage());
    }

    @Test
    @DisplayName("unknown result variable is rejected")
    void unknownResultVariableIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("unknown_result");
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("missing", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    @DisplayName("ref result variable is rejected")
    void refResultVariableIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("ref_result");
        func.createAndAddRefVariable("result", new GdArrayType(GdVariantType.VARIANT));
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("result", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("cannot be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("Packed*Array result is rejected")
    void packedArrayResultIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("packed_result");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("packed", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must be non-ref Array or Dictionary"), ex.getMessage());
    }

    @Test
    @DisplayName("non-container builtin result is rejected")
    void nonContainerResultIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("int_result");
        func.createAndAddVariable("result", GdIntType.INT);
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("result", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must be non-ref Array or Dictionary"), ex.getMessage());
    }

    @Test
    @DisplayName("unknown operand variable is rejected")
    void unknownOperandVariableIsRejected() {
        var clazz = newTestClass();
        var func = newFunction("unknown_operand");
        func.createAndAddVariable("arr", new GdArrayType(GdVariantType.VARIANT));
        entry(func).appendInstruction(new ConstructContainerLiteralInsn(
                "arr",
                List.of(new LirInstruction.VariableOperand("missing_elem"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    @DisplayName("Array[Variant] stays on plain constructor path without typed metadata")
    void arrayOfVariantStaysOnPlainPath() {
        var body = generateFilledArray(new GdArrayType(GdVariantType.VARIANT), List.of(GdIntType.INT));
        assertTrue(body.contains("godot_new_Array()"), body);
        assertFalse(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertFalse(body.contains("__gdcc_tmp_array_script_"), body);
    }

    @Test
    @DisplayName("Dictionary[Variant, Variant] stays on plain constructor path without typed metadata")
    void dictionaryOfVariantStaysOnPlainPath() {
        var body = generateFilledDictionary(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                List.of(GdIntType.INT, GdStringType.STRING)
        );
        assertTrue(body.contains("godot_new_Dictionary()"), body);
        assertFalse(
                body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"),
                body
        );
    }

    private String generateFilledArray(GdArrayType arrayType, List<? extends gd.script.gdcc.type.GdType> elementTypes) {
        var clazz = newTestClass();
        var func = newFunction("fill_array");
        var operands = new ArrayList<LirInstruction.Operand>(elementTypes.size());
        for (var i = 0; i < elementTypes.size(); i++) {
            var id = "elem_" + i;
            func.createAndAddVariable(id, elementTypes.get(i));
            operands.add(new LirInstruction.VariableOperand(id));
        }
        func.createAndAddVariable("arr", arrayType);
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("arr", operands));
        clazz.addFunction(func);
        return generateBody(clazz, func);
    }

    private String generateFilledDictionary(
            GdDictionaryType dictionaryType,
            List<? extends gd.script.gdcc.type.GdType> keyValueTypes
    ) {
        var clazz = newTestClass();
        var func = newFunction("fill_dictionary");
        var operands = new ArrayList<LirInstruction.Operand>(keyValueTypes.size());
        for (var i = 0; i < keyValueTypes.size(); i++) {
            var id = "kv_" + i;
            func.createAndAddVariable(id, keyValueTypes.get(i));
            operands.add(new LirInstruction.VariableOperand(id));
        }
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("dict", operands));
        clazz.addFunction(func);
        return generateBody(clazz, func);
    }

    private LirClassDef newTestClass() {
        return new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private LirFunctionDef newFunction(String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private String generateBody(LirClassDef clazz, LirFunctionDef func) {
        // Full API so runtime-provided Array/Dictionary method symbols resolve during usage recording.
        try {
            return generateBody(clazz, func, ExtensionApiLoader.loadVersion(GodotVersion.V451));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to load Extension API for container literal codegen test", ex);
        }
    }

    private String generateBody(LirClassDef clazz, LirFunctionDef func, ExtensionAPI api) {
        var module = new LirModule("test_module", List.of(clazz));
        var classRegistry = new ClassRegistry(api);
        classRegistry.addGdccClass(clazz);
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        return codegen.generateFuncBody(clazz, func);
    }
}
