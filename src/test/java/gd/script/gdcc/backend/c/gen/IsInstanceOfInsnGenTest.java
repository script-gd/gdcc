package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.IsInstanceOfInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPackedStringArrayType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Positive/negative codegen coverage for backend dispatch on `is_instance_of`.
class IsInstanceOfInsnGenTest {
    @Test
    @DisplayName("exact int is int folds to true")
    void exactBuiltinSameFoldsTrue() {
        var body = generate("int", GdIntType.INT, "int");
        assertTrue(body.contains("$result = true;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("gdcc_is_instance_of"), body);
    }

    @Test
    @DisplayName("exact float is int folds to false")
    void exactBuiltinMismatchFoldsFalse() {
        var body = generate("float", GdFloatType.FLOAT, "int");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
    }

    @Test
    @DisplayName("Variant is int uses variant type enum comparison")
    void variantIsBuiltinUsesTypeEnum() {
        var body = generate("value", GdVariantType.VARIANT, "int");
        assertTrue(body.contains("godot_variant_get_type"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        assertFalse(body.contains("ClassDB_is_parent_class"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
        assertFalse(body.contains("$result = true;"), body);
        assertFalse(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("Variant is Node uses object helper, not bare type enum alone")
    void variantIsObjectUsesObjectHelper() {
        var body = generateWithEngine("value", GdVariantType.VARIANT, "Node");
        assertTrue(body.contains("gdcc_is_instance_of_object_variant"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
        assertFalse(body.contains("gdcc_check_variant_type_object"), body);
        assertFalse(body.contains("godot_variant_get_type($value) == GDEXTENSION_VARIANT_TYPE_OBJECT"), body);
    }

    @Test
    @DisplayName("static Node2D is Node emits null check (proven upcast)")
    void objectUpcastEmitsNullCheck() {
        var body = generateWithEngine("obj", new GdObjectType("Node2D"), "Node");
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
        assertFalse(body.contains("$result = true;"), body);
    }

    @Test
    @DisplayName("static Node is Node2D stays runtime (parent to child open)")
    void objectParentToChildIsRuntime() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "Node2D");
        assertTrue(body.contains("gdcc_is_instance_of_object_raw_and_id"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node2D\")"), body);
        assertFalse(body.contains("$result = true;"), body);
        assertFalse(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("Variant is Variant folds true (top type; no crash / no NIL enum path)")
    void variantTargetWithVariantOperandFoldsTrue() {
        var body = generate("value", GdVariantType.VARIANT, "Variant");
        assertTrue(body.contains("$result = true;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("GDEXTENSION_VARIANT_TYPE_NIL"), body);
        assertFalse(body.contains("gdcc_is_instance_of"), body);
    }

    @Test
    @DisplayName("int is Variant folds true (hand-written LIR mirror of frontend top-type fold)")
    void variantTargetWithBuiltinOperandFoldsTrue() {
        var body = generate("value", GdIntType.INT, "Variant");
        assertTrue(body.contains("$result = true;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("GDEXTENSION_VARIANT_TYPE_NIL"), body);
    }

    @Test
    @DisplayName("null/Nil is Variant folds true (top type overrides Nil→false for other targets)")
    void variantTargetWithNilOperandFoldsTrue() {
        var body = generate("n", GdNilType.NIL, "Variant");
        assertTrue(body.contains("$result = true;"), body);
        assertFalse(body.contains("$result = false;"), body);
        assertFalse(body.contains("gdcc_is_instance_of"), body);
    }

    @Test
    @DisplayName("Node is Variant folds true (object family no longer XOR-false vs Variant target)")
    void variantTargetWithObjectOperandFoldsTrue() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "Variant");
        assertTrue(body.contains("$result = true;"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
        assertFalse(body.contains("gdcc_object_is_null_raw_and_id"), body);
    }

    @Test
    @DisplayName("null/Nil is Node folds to false")
    void nilIsObjectFoldsFalse() {
        var body = generateWithEngine("n", GdNilType.NIL, "Node");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
    }

    @Test
    @DisplayName("exact int is Node folds to false (disjoint families)")
    void builtinIsObjectFoldsFalse() {
        var body = generateWithEngine("value", GdIntType.INT, "Node");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
    }

    @Test
    @DisplayName("static Node is int folds to false")
    void objectIsBuiltinFoldsFalse() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "int");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
    }

    @Test
    @DisplayName("UNRESOLVED_OBJECT target never folds even for exact object static type")
    void unresolvedObjectTargetNeverFolds() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "FutureEnemy");
        assertTrue(body.contains("gdcc_is_instance_of_object_raw_and_id"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"FutureEnemy\")"), body);
        assertFalse(body.contains("$result = true;"), body);
        assertFalse(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("UNRESOLVED_OBJECT with Variant value uses object variant helper")
    void unresolvedObjectWithVariantUsesRuntimeHelper() {
        var body = generate("value", GdVariantType.VARIANT, "FutureEnemy");
        assertTrue(body.contains("gdcc_is_instance_of_object_variant"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"FutureEnemy\")"), body);
        assertFalse(body.contains("$result = true;"), body);
        assertFalse(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("Variant is Array[int] uses typed-array helper not bare ARRAY enum equality alone")
    void variantIsTypedArrayUsesHelper() {
        var body = generate("value", GdVariantType.VARIANT, "Array[int]");
        assertTrue(body.contains("gdcc_is_instance_of_typed_array_variant"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        // Must not reduce Array[int] to a bare ARRAY type-enum check.
        assertFalse(body.matches("(?s).*godot_variant_get_type\\([^)]*\\)\\s*==\\s*GDEXTENSION_VARIANT_TYPE_ARRAY.*"), body);
    }

    @Test
    @DisplayName("Variant is Array[Node] passes object-leaf class name to typed-array helper")
    void variantIsTypedObjectArrayUsesHelperWithClassName() {
        var body = generateWithEngine("value", GdVariantType.VARIANT, "Array[Node]");
        assertTrue(body.contains("gdcc_is_instance_of_typed_array_variant"), body);
        assertTrue(body.contains("(godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
    }

    @Test
    @DisplayName("Variant is Dictionary[String, Node] passes object value-leaf class name")
    void variantIsTypedObjectDictionaryUsesHelperWithClassName() {
        var body = generateWithEngine("value", GdVariantType.VARIANT, "Dictionary[String, Node]");
        assertTrue(body.contains("gdcc_is_instance_of_typed_dictionary_variant"), body);
        assertTrue(body.contains("(godot_int)GDEXTENSION_VARIANT_TYPE_STRING"), body);
        assertTrue(body.contains("(godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
    }

    @Test
    @DisplayName("Variant is bare Array uses type-enum comparison not typed helper")
    void variantIsBareArrayUsesTypeEnum() {
        var body = generate("value", GdVariantType.VARIANT, "Array");
        assertTrue(body.contains("godot_variant_get_type"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_ARRAY"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_array"), body);
    }

    @Test
    @DisplayName("Variant is PackedInt32Array uses type enum, not typed-array helper or ClassDB")
    void variantIsPackedInt32ArrayUsesTypeEnum() {
        var body = generate("value", GdVariantType.VARIANT, "PackedInt32Array");
        assertTrue(body.contains("godot_variant_get_type"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_array"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
        assertFalse(body.contains("ClassDB_is_parent_class"), body);
        assertFalse(body.contains("$result = true;"), body);
        assertFalse(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("Variant is PackedStringArray uses PACKED_STRING_ARRAY type enum")
    void variantIsPackedStringArrayUsesTypeEnum() {
        var body = generate("value", GdVariantType.VARIANT, "PackedStringArray");
        assertTrue(body.contains("godot_variant_get_type"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_array"), body);
    }

    @Test
    @DisplayName("exact static PackedInt32Array is PackedInt32Array folds to true")
    void staticPackedExactMatchFoldsTrue() {
        var body = generate("p", GdPackedNumericArrayType.PACKED_INT32_ARRAY, "PackedInt32Array");
        assertTrue(body.contains("$result = true;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("gdcc_is_instance_of"), body);
    }

    @Test
    @DisplayName("static PackedInt32Array is PackedFloat32Array folds to false")
    void staticPackedMismatchFoldsFalse() {
        var body = generate("p", GdPackedNumericArrayType.PACKED_INT32_ARRAY, "PackedFloat32Array");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_array"), body);
    }

    @Test
    @DisplayName("static PackedStringArray is PackedInt32Array folds to false")
    void staticPackedStringVsIntFoldsFalse() {
        var body = generate("p", GdPackedStringArrayType.PACKED_STRING_ARRAY, "PackedInt32Array");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
    }

    @Test
    @DisplayName("static PackedInt32Array is bare Array folds to false (distinct Variant types)")
    void staticPackedIsBareArrayFoldsFalse() {
        var body = generate("p", GdPackedNumericArrayType.PACKED_INT32_ARRAY, "Array");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_array"), body);
    }

    @Test
    @DisplayName("Variant is bare Dictionary uses type-enum comparison not typed helper")
    void variantIsBareDictionaryUsesTypeEnum() {
        var body = generate("value", GdVariantType.VARIANT, "Dictionary");
        assertTrue(body.contains("godot_variant_get_type"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_DICTIONARY"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed_dictionary"), body);
    }

    @Test
    @DisplayName("disjoint builtin value vs unresolved object target still folds false")
    void disjointBuiltinVsUnresolvedObjectFoldsFalse() {
        // UNRESOLVED_OBJECT forbids object-true folds, but an exact non-object value can never
        // be an object instance; emitting false is intentional and cheaper than a no-op runtime call.
        var body = generate("value", GdIntType.INT, "FutureEnemy");
        assertTrue(body.contains("$result = false;"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
    }

    @Test
    @DisplayName("static Array is Array[int] stays runtime-open via typed-array helper")
    void bareArrayIsTypedArrayStaysRuntimeOpen() {
        // Bare Array slots may carry typed metadata at runtime; do not fold false statically.
        var body = generate("arr", new GdArrayType(GdVariantType.VARIANT), "Array[int]");
        assertTrue(body.contains("gdcc_is_instance_of_typed_array"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        assertFalse(body.contains("$result = false;"), body);
        assertFalse(body.contains("$result = true;"), body);
    }

    @Test
    @DisplayName("static Dictionary is Dictionary[String, int] stays runtime-open via typed helper")
    void bareDictionaryIsTypedDictionaryStaysRuntimeOpen() {
        var body = generate(
                "dict",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                "Dictionary[String, int]"
        );
        assertTrue(body.contains("gdcc_is_instance_of_typed_dictionary"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        assertFalse(body.contains("$result = false;"), body);
        assertFalse(body.contains("$result = true;"), body);
    }

    @Test
    @DisplayName("static Array[int] is Array[int] folds true")
    void typedArrayExactMatchFoldsTrue() {
        var body = generate("arr", new GdArrayType(GdIntType.INT), "Array[int]");
        assertTrue(body.contains("$result = true;"), body);
    }

    @Test
    @DisplayName("static Array[int] is Array folds true (typed is bare Array)")
    void typedArrayIsBareArrayFoldsTrue() {
        var body = generate("arr", new GdArrayType(GdIntType.INT), "Array");
        assertTrue(body.contains("$result = true;"), body);
    }

    @Test
    @DisplayName("static Array[String] is Array[int] stays runtime typed helper when not folded")
    void typedArrayMismatchUsesRuntimeWhenNotSame() {
        // Frontend would fold false; backend second insurance also folds false via exact mismatch.
        var body = generate("arr", new GdArrayType(GdStringType.STRING), "Array[int]");
        assertTrue(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("Variant is Dictionary[String, int] uses typed-dictionary helper")
    void variantIsTypedDictionaryUsesHelper() {
        var body = generate("value", GdVariantType.VARIANT, "Dictionary[String, int]");
        assertTrue(body.contains("gdcc_is_instance_of_typed_dictionary_variant"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
    }

    @Test
    @DisplayName("static Dictionary[String, int] value uses typed-dictionary handle helper")
    void staticTypedDictionaryIsTypedDictionaryUsesHelperWhenOpen() {
        // Same static type folds; use Dictionary[String, float] vs Dictionary[String, int] for false fold.
        var body = generate(
                "dict",
                new GdDictionaryType(GdStringType.STRING, GdFloatType.FLOAT),
                "Dictionary[String, int]"
        );
        assertTrue(body.contains("$result = false;"), body);
    }

    @Test
    @DisplayName("static Dictionary value with matching parameterized target folds true")
    void typedDictionaryExactMatchFoldsTrue() {
        var body = generate(
                "dict",
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                "Dictionary[String, int]"
        );
        assertTrue(body.contains("$result = true;"), body);
    }

    @Test
    @DisplayName("object fat pointer same type emits null check (proven exact)")
    void staticObjectExactEmitsNullCheck() {
        var body = generateWithEngine("obj", new GdObjectType("Node"), "Node");
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id"), body);
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
    }

    @Test
    @DisplayName("unsupported nested structured type_name fails closed")
    void nestedStructuredTypeNameFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class, () ->
                generate("value", GdVariantType.VARIANT, "Array[Array[int]]"));
        assertTrue(ex.getMessage().contains("cannot be resolved")
                        || ex.getMessage().contains("does not support"),
                ex.getMessage());
    }

    @Test
    @DisplayName("empty type_name fails closed")
    void emptyTypeNameFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class, () ->
                generate("value", GdVariantType.VARIANT, "   "));
        assertTrue(ex.getMessage().contains("must not be empty"), ex.getMessage());
    }

    @Test
    @DisplayName("null resultId is a validated no-op")
    void nullResultIdIsNoOp() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unused_result");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new IsInstanceOfInsn(null, "int", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = emptyApi();
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));
        var body = codegen.generateFuncBody(workerClass, func);

        assertFalse(body.contains("godot_variant_get_type"), body);
        assertFalse(body.contains("gdcc_is_instance_of"), body);
        assertFalse(body.contains("$result"), body);
        assertFalse(body.contains(" = true;"), body);
        assertFalse(body.contains(" = false;"), body);
    }

    @Test
    @DisplayName("null resultId still validates empty type_name")
    void nullResultIdStillValidatesTypeName() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("unused_empty_type");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new IsInstanceOfInsn(null, "   ", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = emptyApi();
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("must not be empty"), ex.getMessage());
    }

    @Test
    @DisplayName("malformed type_name fails closed instead of unresolved object path")
    void malformedTypeNameFailsClosed() {
        var ex = assertThrows(InvalidInsnException.class, () ->
                generate("value", GdVariantType.VARIANT, "123Bad"));
        assertTrue(ex.getMessage().contains("cannot be resolved"), ex.getMessage());
    }

    @Test
    @DisplayName("missing value variable fails closed")
    void missingValueVariableFailsClosed() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("missing_value");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new IsInstanceOfInsn("result", "int", "missing"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = emptyApi();
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("value variable not found"), ex.getMessage());
    }

    @Test
    @DisplayName("non-bool result fails closed")
    void nonBoolResultFailsClosed() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("bad_result");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new IsInstanceOfInsn("result", "int", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = emptyApi();
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertTrue(ex.getMessage().contains("must be bool"), ex.getMessage());
    }

    @Test
    @DisplayName("never reuses unpack helper gdcc_check_variant_type_object for is")
    void neverReusesUnpackObjectTypeCheckHelper() {
        var body = generateWithEngine("value", GdVariantType.VARIANT, "Node");
        assertFalse(body.contains("gdcc_check_variant_type_object"), body);
        assertTrue(body.contains("gdcc_is_instance_of_object_variant"), body);
    }

    @Test
    @DisplayName("is_instance_of + unary_op NOT produces correct C for is-not path")
    void isInstanceOfPlusUnaryNotProducesCorrectC() {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("is_not_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("value", GdVariantType.VARIANT);
        func.createAndAddVariable("tmp", GdBoolType.BOOL);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new IsInstanceOfInsn("tmp", "int", "value"));
        entry.appendInstruction(new UnaryOpInsn("result", GodotOperator.NOT, "tmp"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var api = emptyApi();
        var codegen = new CCodegen();
        codegen.prepare(newContext(api, List.of(workerClass)), new LirModule("test_module", List.of(workerClass)));
        var body = codegen.generateFuncBody(workerClass, func);

        assertTrue(body.contains("godot_variant_get_type"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_INT"), body);
        assertTrue(body.contains("$tmp"), body);
        assertTrue(body.contains("$result = gdcc_eval_unary_not_bool_to_bool($tmp);"), body);
    }

    private static @NotNull String generate(@NotNull String valueId, @NotNull GdType valueType, @NotNull String typeName) {
        return generate(emptyApi(), List.of(), valueId, valueType, typeName);
    }

    private static @NotNull String generateWithEngine(
            @NotNull String valueId,
            @NotNull GdType valueType,
            @NotNull String typeName
    ) {
        return generate(engineApi(), List.of(), valueId, valueType, typeName);
    }

    private static @NotNull String generate(
            @NotNull ExtensionAPI api,
            @NotNull List<LirClassDef> extraClasses,
            @NotNull String valueId,
            @NotNull GdType valueType,
            @NotNull String typeName
    ) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("type_test");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable(valueId, valueType);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new IsInstanceOfInsn("result", typeName, valueId));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var classes = new java.util.ArrayList<LirClassDef>();
        classes.add(workerClass);
        classes.addAll(extraClasses);

        var codegen = new CCodegen();
        codegen.prepare(newContext(api, classes), new LirModule("test_module", classes));
        return codegen.generateFuncBody(workerClass, func);
    }

    private static @NotNull ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static @NotNull ExtensionAPI engineApi() {
        var objectClass = new ExtensionGdClass(
                "Object", false, false, "", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var nodeClass = new ExtensionGdClass(
                "Node", false, false, "Object", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        var node2dClass = new ExtensionGdClass(
                "Node2D", false, false, "Node", "core",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(objectClass, nodeClass, node2dClass),
                List.of(),
                List.of()
        );
    }

    private static @NotNull CodegenContext newContext(@NotNull ExtensionAPI api, @NotNull List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, classRegistry, true);
    }
}
