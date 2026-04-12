package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.gdextension.ExtensionSingleton;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.insn.ConstructArrayInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructDictionaryInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdPackedStringArrayType;
import dev.superice.gdcc.type.GdPackedVectorArrayType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdTransform2DType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CConstructInsnGenTest {
    @Test
    @DisplayName("construct_builtin should emit helper-shim constructor call for Transform2D with 6 float args")
    void constructBuiltinShouldEmitTransform2DHelperCtor() {
        var clazz = newTestClass();
        var func = newFunction("construct_transform2d");
        func.createAndAddVariable("a", GdFloatType.FLOAT);
        func.createAndAddVariable("b", GdFloatType.FLOAT);
        func.createAndAddVariable("c", GdFloatType.FLOAT);
        func.createAndAddVariable("d", GdFloatType.FLOAT);
        func.createAndAddVariable("e", GdFloatType.FLOAT);
        func.createAndAddVariable("f", GdFloatType.FLOAT);
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "t",
                List.of(
                        new LirInstruction.VariableOperand("a"),
                        new LirInstruction.VariableOperand("b"),
                        new LirInstruction.VariableOperand("c"),
                        new LirInstruction.VariableOperand("d"),
                        new LirInstruction.VariableOperand("e"),
                        new LirInstruction.VariableOperand("f")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Transform2D_with_float_float_float_float_float_float"));
    }

    @Test
    @DisplayName("construct_builtin should reject non-variable operands")
    void constructBuiltinShouldRejectNonVariableOperand() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_non_var_operand");
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "t",
                List.of(new LirInstruction.StringOperand("not_var"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must be a variable operand"));
    }

    @Test
    @DisplayName("construct_array should emit typed Array constructor when operand type matches result type")
    void constructArrayShouldEmitTypedCtor() {
        var clazz = newTestClass();
        var func = newFunction("construct_typed_array");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", "StringName"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING_NAME"));
        assertTrue(body.contains("godot_Variant __gdcc_tmp_array_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var arrayCtorCall = extractCall(body, "godot_new_Array_with_Array_int_StringName_Variant");
        assertFalse(arrayCtorCall.contains("NULL"), arrayCtorCall);
    }

    @Test
    @DisplayName("construct_array should fail when provided class_name does not match result element type")
    void constructArrayShouldRejectTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_array_mismatch");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", "String"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_array type mismatch"));
    }

    @Test
    @DisplayName("construct_array should keep generic Array construction on the plain constructor path")
    void constructArrayShouldKeepGenericCtorOnPlainPath() {
        var clazz = newTestClass();
        var func = newFunction("construct_generic_array");
        func.createAndAddVariable("arr", new GdArrayType(GdVariantType.VARIANT));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array()"), body);
        assertFalse(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertFalse(body.contains("__gdcc_tmp_array_script_"), body);
        assertFalse(body.contains("godot_new_Variant_nil();"), body);
    }

    @Test
    @DisplayName("construct_array should emit Packed*Array constructor when result type is packed and class_name is omitted")
    void constructArrayShouldEmitPackedCtorWhenClassNameOmitted() {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);

        entry(func).appendInstruction(new ConstructArrayInsn("packed", null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_PackedInt32Array()"));
    }

    @Test
    @DisplayName("construct_array should reject class_name when result type is Packed*Array")
    void constructArrayShouldRejectClassNameForPackedArray() {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array_with_class_name");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);

        entry(func).appendInstruction(new ConstructArrayInsn("packed", "PackedInt32Array"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must not provide class_name"));
    }

    @Test
    @DisplayName("construct_array should reject empty or blank class_name when result type is Packed*Array")
    void constructArrayShouldRejectEmptyOrBlankClassNameForPackedArray() {
        assertPackedArrayClassNameRejected("");
        assertPackedArrayClassNameRejected("   ");
    }

    @Test
    @DisplayName("construct_dictionary should emit typed Dictionary constructor when key/value operands match result types")
    void constructDictionaryShouldEmitTypedCtor() {
        var clazz = newTestClass();
        var func = newFunction("construct_typed_dictionary");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "StringName", "Variant"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING_NAME"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_NIL"));
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_key_script_"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_value_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var dictCtorCall = extractCall(body, "godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant");
        assertFalse(dictCtorCall.contains("NULL"), dictCtorCall);
    }

    @Test
    @DisplayName("construct_dictionary should fail when provided key/value types do not match result types")
    void constructDictionaryShouldRejectTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_mismatch");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "String", "Variant"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary key type mismatch"));
    }

    @Test
    @DisplayName("construct_array should preserve unknown object leaf hints through registry compatibility parsing")
    void constructArrayShouldPreserveUnknownObjectLeafHints() {
        var clazz = newTestClass();
        var func = newFunction("construct_array_unknown_object_leaf");
        func.createAndAddVariable("arr", new GdArrayType(new GdObjectType("FutureItem")));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", "FutureItem"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("GD_STATIC_SN(u8\"FutureItem\")"));
    }

    @Test
    @DisplayName("construct_dictionary should preserve unknown object leaf hints through registry compatibility parsing")
    void constructDictionaryShouldPreserveUnknownObjectLeafHints() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_unknown_object_leaf");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringType.STRING, new GdObjectType("FutureItem")));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "String", "FutureItem"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("GD_STATIC_SN(u8\"FutureItem\")"));
    }

    @Test
    @DisplayName("construct container hints should reject non-type registry names exposed by compatibility lookup")
    void constructContainerHintsShouldRejectNonTypeRegistryNames() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("DamageFlags", true, List.of())),
                List.of(new ExtensionUtilityFunction("spawn_helper", "void", "test", false, 0, List.of())),
                List.of(),
                List.of(),
                List.of(new ExtensionSingleton("GameSingleton", "Node")),
                List.of()
        );

        assertInvalidArrayHint(api, "DamageFlags");
        assertInvalidArrayHint(api, "spawn_helper");
        assertInvalidArrayHint(api, "GameSingleton");
    }

    @Test
    @DisplayName("construct_dictionary should fail when one-side typed operand implies Variant but result value type is non-Variant")
    void constructDictionaryShouldRejectImplicitVariantValueMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_partial_operand_mismatch");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdStringType.STRING));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "StringName", null));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary value type mismatch"));
    }

    @Test
    @DisplayName("construct_object should emit direct gdextension-lite constructor call for engine object targets")
    void constructObjectShouldEmitEngineConstructCall() {
        var clazz = newTestClass();
        var func = newFunction("construct_engine_object");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).appendInstruction(new ConstructObjectInsn("node", "Node"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("godot_new_Node()"));
        assertFalse(body.contains("gdcc_object_from_godot_object_ptr("), body);
    }

    @Test
    @DisplayName("construct_object should consume fresh engine RefCounted results without extra own")
    void constructObjectShouldNotRetainFreshEngineRefCountedResultAgain() {
        var clazz = newTestClass();
        var func = newFunction("construct_engine_refcounted");
        func.createAndAddVariable("resource", new GdObjectType("RefCounted"));

        entry(func).appendInstruction(new ConstructObjectInsn("resource", "RefCounted"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("$resource = godot_new_RefCounted();"), body);
        assertFalse(body.contains("own_object($resource);"), body);
        assertFalse(body.contains("try_own_object($resource);"), body);
    }

    @Test
    @DisplayName("construct_object should externally initialize RefCounted gdcc create_instance results and convert into gdcc wrapper target")
    void constructObjectShouldConvertToGdccRefCountedWrapperTarget() {
        var holderClass = new LirClassDef("Holder", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = newFunction("construct_gdcc_object");
        func.createAndAddVariable("worker", new GdObjectType("Worker"));

        entry(func).appendInstruction(new ConstructObjectInsn("worker", "Worker"));
        holderClass.addFunction(func);

        var module = new LirModule("test_module", List.of(holderClass, workerClass));
        var codegen = newCodegen(module, List.of(holderClass, workerClass), apiWithConstructibleObjectClasses());
        var body = codegen.generateFuncBody(holderClass, func);

        assertTrue(body.contains("gdcc_ref_counted_init_raw(Worker_class_create_instance(NULL, false), true)"));
        assertTrue(body.contains("gdcc_object_from_godot_object_ptr("), body);
        assertFalse(body.contains("own_object("), body);
        assertFalse(body.contains("try_own_object("), body);
    }

    @Test
    @DisplayName("construct_object should keep plain gdcc create_instance raw when target is not RefCounted")
    void constructObjectShouldKeepPlainGdccCreateInstanceRaw() {
        var holderClass = new LirClassDef("Holder", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var plainClass = new LirClassDef("PlainWorker", "Object");
        var func = newFunction("construct_plain_gdcc_object");
        func.createAndAddVariable("worker", new GdObjectType("PlainWorker"));

        entry(func).appendInstruction(new ConstructObjectInsn("worker", "PlainWorker"));
        holderClass.addFunction(func);

        var module = new LirModule("test_module", List.of(holderClass, plainClass));
        var codegen = newCodegen(module, List.of(holderClass, plainClass), apiWithConstructibleObjectClasses());
        var body = codegen.generateFuncBody(holderClass, func);

        assertTrue(body.contains("PlainWorker_class_create_instance(NULL, true)"), body);
        assertFalse(body.contains("gdcc_ref_counted_init_raw("), body);
        assertTrue(body.contains("gdcc_object_from_godot_object_ptr("), body);
    }

    @Test
    @DisplayName("construct_object should reject non-object result slots")
    void constructObjectShouldRejectNonObjectResultSlot() {
        var clazz = newTestClass();
        var func = newFunction("construct_object_non_object_slot");
        func.createAndAddVariable("value", GdFloatType.FLOAT);

        entry(func).appendInstruction(new ConstructObjectInsn("value", "Node"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("must be Object type for construct_object"));
    }

    @Test
    @DisplayName("construct_object should reject unknown classes")
    void constructObjectShouldRejectUnknownClass() {
        var clazz = newTestClass();
        var func = newFunction("construct_unknown_object");
        func.createAndAddVariable("obj", new GdObjectType("UnknownType"));

        entry(func).appendInstruction(new ConstructObjectInsn("obj", "UnknownType"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("class 'UnknownType' is not registered"));
    }

    @Test
    @DisplayName("construct_object should reject non-instantiable engine classes")
    void constructObjectShouldRejectNonInstantiableEngineClass() {
        var clazz = newTestClass();
        var func = newFunction("construct_non_instantiable_engine");
        func.createAndAddVariable("obj", new GdObjectType("EditorOnlyThing"));

        entry(func).appendInstruction(new ConstructObjectInsn("obj", "EditorOnlyThing"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("class 'EditorOnlyThing' is not instantiable"));
    }

    @Test
    @DisplayName("construct_object should reject abstract gdcc classes")
    void constructObjectShouldRejectAbstractGdccClass() {
        var holderClass = newTestClass();
        var abstractWorker = new LirClassDef("AbstractWorker", "RefCounted");
        abstractWorker.setAbstract(true);
        var func = newFunction("construct_abstract_gdcc");
        func.createAndAddVariable("worker", new GdObjectType("AbstractWorker"));

        entry(func).appendInstruction(new ConstructObjectInsn("worker", "AbstractWorker"));
        holderClass.addFunction(func);

        var module = new LirModule("test_module", List.of(holderClass, abstractWorker));
        var codegen = newCodegen(module, List.of(holderClass, abstractWorker), apiWithConstructibleObjectClasses());
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(holderClass, func));

        assertTrue(ex.getMessage().contains("class 'AbstractWorker' is abstract"));
    }

    @Test
    @DisplayName("construct_object should reject class targets that are not assignable to result slot type")
    void constructObjectShouldRejectIncompatibleResultType() {
        var clazz = newTestClass();
        var func = newFunction("construct_object_type_mismatch");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).appendInstruction(new ConstructObjectInsn("node", "RefCounted"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("is not assignable to result variable type 'Node'"));
    }

    @Test
    @DisplayName("generate should inject typed construct instructions into __prepare__ for Array and Dictionary variables")
    void generateShouldInjectConstructInstructionsIntoPrepareBlock() {
        var clazz = newTestClass();
        var func = newFunction("prepare_inject_constructs");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepare = func.getBasicBlock("__prepare__");
        assertNotNull(prepare);
        assertEquals("__prepare__", func.getEntryBlockId());

        var hasArrayInsn = prepare.getInstructions().stream()
                .filter(ConstructArrayInsn.class::isInstance)
                .map(ConstructArrayInsn.class::cast)
                .anyMatch(insn -> "arr".equals(insn.resultId()) && "StringName".equals(insn.className()));
        assertTrue(hasArrayInsn);

        var hasDictionaryInsn = prepare.getInstructions().stream()
                .filter(ConstructDictionaryInsn.class::isInstance)
                .map(ConstructDictionaryInsn.class::cast)
                .anyMatch(insn ->
                        "dict".equals(insn.resultId()) &&
                                "StringName".equals(insn.keyClassName()) &&
                                "Variant".equals(insn.valueClassName())
                );
        assertTrue(hasDictionaryInsn);
    }

    @Test
    @DisplayName("generate should inject construct_array with null class_name into __prepare__ for Packed*Array variables")
    void generateShouldInjectPackedConstructArrayIntoPrepareBlock() {
        var clazz = newTestClass();
        var func = newFunction("prepare_inject_packed_construct");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepare = func.getBasicBlock("__prepare__");
        assertNotNull(prepare);
        var hasPackedArrayInsn = prepare.getInstructions().stream()
                .filter(ConstructArrayInsn.class::isInstance)
                .map(ConstructArrayInsn.class::cast)
                .anyMatch(insn -> "packed".equals(insn.resultId()) && insn.className() == null);
        assertTrue(hasPackedArrayInsn);

        var hasPackedBuiltinInsn = prepare.getInstructions().stream()
                .filter(ConstructBuiltinInsn.class::isInstance)
                .map(ConstructBuiltinInsn.class::cast)
                .anyMatch(insn -> "packed".equals(insn.resultId()));
        assertFalse(hasPackedBuiltinInsn);
    }

    @Test
    @DisplayName("__prepare__ generated typed construct instructions should emit typed constructor C calls")
    void generatedPrepareConstructsShouldEmitTypedConstructorCalls() {
        var clazz = newTestClass();
        var func = newFunction("prepare_emit_typed_ctor_calls");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var body = codegen.generateFuncBody(clazz, func);
        assertTrue(body.contains("__prepare__: // __prepare__"));
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("godot_Variant __gdcc_tmp_array_script_"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_key_script_"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_value_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var arrayCtorCall = extractCall(body, "godot_new_Array_with_Array_int_StringName_Variant");
        assertFalse(arrayCtorCall.contains("NULL"), arrayCtorCall);
        var dictCtorCall = extractCall(body, "godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant");
        assertFalse(dictCtorCall.contains("NULL"), dictCtorCall);
    }

    @Test
    @DisplayName("__prepare__ generated Packed*Array construct instruction should emit packed constructor C call")
    void generatedPreparePackedConstructShouldEmitPackedConstructorCall() {
        var clazz = newTestClass();
        var func = newFunction("prepare_emit_packed_ctor_call");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var body = codegen.generateFuncBody(clazz, func);
        assertTrue(body.contains("__prepare__: // __prepare__"));
        assertTrue(body.contains("godot_new_PackedInt32Array()"));
    }

    @Test
    @DisplayName("construct_array should emit packed constructors for all supported Packed*Array types")
    void constructArrayShouldEmitPackedCtorForAllSupportedPackedArrayTypes() {
        for (var packedCase : packedCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("construct_" + packedCase.label() + "_array");
            func.createAndAddVariable("packed", packedCase.type());
            entry(func).appendInstruction(new ConstructArrayInsn("packed", null));
            clazz.addFunction(func);

            var body = generateBody(clazz, func);
            assertTrue(
                    body.contains(packedCase.constructorCall()),
                    () -> "Expected constructor call missing for " + packedCase.typeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("__prepare__ should inject packed construct_array for all supported Packed*Array types")
    void generateShouldInjectPackedConstructArrayIntoPrepareBlockForAllPackedTypes() {
        for (var packedCase : packedCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("prepare_inject_" + packedCase.label());
            func.createAndAddVariable("packed", packedCase.type());
            entry(func).appendInstruction(new ReturnInsn(null));
            clazz.addFunction(func);

            var module = new LirModule("test_module", List.of(clazz));
            var codegen = newCodegen(module, List.of(clazz));
            codegen.generate();

            var prepare = func.getBasicBlock("__prepare__");
            assertNotNull(prepare);
            var hasPackedArrayInsn = prepare.getInstructions().stream()
                    .filter(ConstructArrayInsn.class::isInstance)
                    .map(ConstructArrayInsn.class::cast)
                    .anyMatch(insn -> "packed".equals(insn.resultId()) && insn.className() == null);
            assertTrue(hasPackedArrayInsn, () -> "Missing construct_array injection for " + packedCase.typeName());

            var hasPackedBuiltinInsn = prepare.getInstructions().stream()
                    .filter(ConstructBuiltinInsn.class::isInstance)
                    .map(ConstructBuiltinInsn.class::cast)
                    .anyMatch(insn -> "packed".equals(insn.resultId()));
            assertFalse(hasPackedBuiltinInsn, () -> "Unexpected construct_builtin injection for " + packedCase.typeName());
        }
    }

    @Test
    @DisplayName("__prepare__ generated packed construct_array should emit constructor calls for all supported Packed*Array types")
    void generatedPreparePackedConstructShouldEmitPackedConstructorCallForAllPackedTypes() {
        for (var packedCase : packedCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("prepare_emit_" + packedCase.label());
            func.createAndAddVariable("packed", packedCase.type());
            entry(func).appendInstruction(new ReturnInsn(null));
            clazz.addFunction(func);

            var module = new LirModule("test_module", List.of(clazz));
            var codegen = newCodegen(module, List.of(clazz));
            codegen.generate();

            var body = codegen.generateFuncBody(clazz, func);
            assertTrue(body.contains("__prepare__: // __prepare__"));
            assertTrue(
                    body.contains(packedCase.constructorCall()),
                    () -> "Expected prepare constructor call missing for " + packedCase.typeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("generate should inject packed construct_array into default field init functions")
    void generateShouldInjectPackedConstructArrayIntoDefaultFieldInitFunctions() {
        var clazz = newTestClass();
        var propertyCases = new ArrayList<PackedPropertyCase>();
        for (var i = 0; i < packedCtorCases().size(); i++) {
            var packedCase = packedCtorCases().get(i);
            var propertyName = "packed_prop_" + i;
            clazz.addProperty(new LirPropertyDef(propertyName, packedCase.type()));
            propertyCases.add(new PackedPropertyCase(propertyName, packedCase));
        }

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        for (var propertyCase : propertyCases) {
            var initFuncName = "_field_init_" + propertyCase.propertyName();
            var initFunc = findFunctionByName(clazz, initFuncName);
            assertNotNull(initFunc, () -> "Missing init function " + initFuncName);

            var entryBlock = initFunc.getBasicBlock(initFunc.getEntryBlockId());
            assertNotNull(entryBlock, () -> "Missing entry block in " + initFuncName);

            var hasConstructArrayInsn = entryBlock.getInstructions().stream()
                    .filter(ConstructArrayInsn.class::isInstance)
                    .map(ConstructArrayInsn.class::cast)
                    .anyMatch(insn -> insn.className() == null);
            assertTrue(
                    hasConstructArrayInsn,
                    () -> "Field init function should use construct_array for " + propertyCase.packedCase().typeName()
            );

            var hasConstructBuiltinInsn = entryBlock.getInstructions().stream()
                    .anyMatch(ConstructBuiltinInsn.class::isInstance);
            assertFalse(
                    hasConstructBuiltinInsn,
                    () -> "Field init function should not use construct_builtin for " + propertyCase.packedCase().typeName()
            );

            var body = codegen.generateFuncBody(clazz, initFunc);
            assertTrue(
                    body.contains(propertyCase.packedCase().constructorCall()),
                    () -> "Expected field init constructor call missing for " + propertyCase.packedCase().typeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("__prepare__ construct_array should fail fast on type mismatch")
    void prepareConstructArrayTypeMismatchShouldFailFast() {
        var clazz = newTestClass();
        var func = new LirFunctionDef("prepare_array_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        var prepare = new LirBasicBlock("__prepare__");
        prepare.appendInstruction(new ConstructArrayInsn("arr", "String"));
        func.addBasicBlock(prepare);
        func.setEntryBlockId("__prepare__");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_array type mismatch"));
    }

    @Test
    @DisplayName("__prepare__ construct_dictionary should fail fast on type mismatch")
    void prepareConstructDictionaryTypeMismatchShouldFailFast() {
        var clazz = newTestClass();
        var func = new LirFunctionDef("prepare_dictionary_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        var prepare = new LirBasicBlock("__prepare__");
        prepare.appendInstruction(new ConstructDictionaryInsn("dict", "String", "Variant"));
        func.addBasicBlock(prepare);
        func.setEntryBlockId("__prepare__");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary key type mismatch"));
    }

    @Test
    @DisplayName("generate should emit typed array constructor with nil script carrier in default field init helpers")
    void generateShouldEmitTypedArrayCtorInDefaultFieldInitHelper() {
        var clazz = newTestClass();
        clazz.addProperty(new LirPropertyDef("payloads", new GdArrayType(GdStringNameType.STRING_NAME)));

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var initFunc = findFunctionByName(clazz, "_field_init_payloads");
        assertNotNull(initFunc);
        assertEquals("__prepare__", initFunc.getEntryBlockId());
        var body = codegen.generateFuncBody(clazz, initFunc);

        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_array_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var arrayCtorCall = extractCall(body, "godot_new_Array_with_Array_int_StringName_Variant");
        assertFalse(arrayCtorCall.contains("NULL"), arrayCtorCall);
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
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        return codegen.generateFuncBody(clazz, func);
    }

    private String generateBody(LirClassDef clazz, LirFunctionDef func, ExtensionAPI api) {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz), api);
        return codegen.generateFuncBody(clazz, func);
    }

    private String extractCall(String body, String callName) {
        var callStart = body.indexOf(callName);
        assertTrue(callStart >= 0, body);
        var callEnd = body.indexOf(");", callStart);
        assertTrue(callEnd >= 0, body);
        return body.substring(callStart, callEnd + 2);
    }

    private void assertPackedArrayClassNameRejected(String className) {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array_with_blank_class_name");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ConstructArrayInsn("packed", className));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must not provide class_name"));
    }

    private void assertInvalidArrayHint(ExtensionAPI api, String hintText) {
        var clazz = newTestClass();
        var func = newFunction("construct_array_invalid_hint_" + hintText);
        func.createAndAddVariable("arr", new GdArrayType(GdVariantType.VARIANT));
        entry(func).appendInstruction(new ConstructArrayInsn("arr", hintText));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, api));
        assertTrue(ex.getMessage().contains("construct_array '" + hintText + "' is not a valid type"));
    }

    private LirFunctionDef findFunctionByName(LirClassDef clazz, String functionName) {
        for (var function : clazz.getFunctions()) {
            if (functionName.equals(function.getName())) {
                return function;
            }
        }
        return null;
    }

    private List<PackedCtorCase> packedCtorCases() {
        return List.of(
                new PackedCtorCase("packed_byte", "PackedByteArray", GdPackedNumericArrayType.PACKED_BYTE_ARRAY),
                new PackedCtorCase("packed_int32", "PackedInt32Array", GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                new PackedCtorCase("packed_int64", "PackedInt64Array", GdPackedNumericArrayType.PACKED_INT64_ARRAY),
                new PackedCtorCase("packed_float32", "PackedFloat32Array", GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY),
                new PackedCtorCase("packed_float64", "PackedFloat64Array", GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY),
                new PackedCtorCase("packed_string", "PackedStringArray", GdPackedStringArrayType.PACKED_STRING_ARRAY),
                new PackedCtorCase("packed_vector2", "PackedVector2Array", GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY),
                new PackedCtorCase("packed_vector3", "PackedVector3Array", GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY),
                new PackedCtorCase("packed_vector4", "PackedVector4Array", GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY)
        );
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        return newCodegen(module, gdccClasses, apiWithPackedConstructors());
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses, ExtensionAPI api) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private ExtensionAPI apiWithPackedConstructors() {
        var packedBuiltins = new ArrayList<ExtensionBuiltinClass>();
        for (var packedCase : packedCtorCases()) {
            packedBuiltins.add(newZeroArgPackedBuiltinClass(packedCase.typeName()));
        }
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                packedBuiltins,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionAPI apiWithConstructibleObjectClasses() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionGdClass("Object", false, true, "", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("RefCounted", true, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("EditorOnlyThing", false, false, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())
                ),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass newZeroArgPackedBuiltinClass(String typeName) {
        return new ExtensionBuiltinClass(
                typeName,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.ConstructorInfo(typeName, 0, List.of())),
                List.of(),
                List.of()
        );
    }

    private record PackedCtorCase(String label, String typeName, GdType type) {
        private String constructorCall() {
            return "godot_new_" + typeName + "()";
        }
    }

    private record PackedPropertyCase(String propertyName, PackedCtorCase packedCase) {
    }
}
