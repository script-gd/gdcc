package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.insn.ConstructArrayInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructDictionaryInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
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

        entry(func).instructions().add(new ConstructBuiltinInsn(
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

        entry(func).instructions().add(new ConstructBuiltinInsn(
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

        entry(func).instructions().add(new ConstructArrayInsn("arr", "StringName"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING_NAME"));
    }

    @Test
    @DisplayName("construct_array should fail when provided class_name does not match result element type")
    void constructArrayShouldRejectTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_array_mismatch");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        entry(func).instructions().add(new ConstructArrayInsn("arr", "String"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_array type mismatch"));
    }

    @Test
    @DisplayName("construct_array should emit Packed*Array constructor when result type is packed and class_name is omitted")
    void constructArrayShouldEmitPackedCtorWhenClassNameOmitted() {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);

        entry(func).instructions().add(new ConstructArrayInsn("packed", null));
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

        entry(func).instructions().add(new ConstructArrayInsn("packed", "PackedInt32Array"));
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

        entry(func).instructions().add(new ConstructDictionaryInsn("dict", "StringName", "Variant"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING_NAME"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_NIL"));
    }

    @Test
    @DisplayName("construct_dictionary should fail when provided key/value types do not match result types")
    void constructDictionaryShouldRejectTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_mismatch");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        entry(func).instructions().add(new ConstructDictionaryInsn("dict", "String", "Variant"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary key type mismatch"));
    }

    @Test
    @DisplayName("construct_dictionary should fail when one-side typed operand implies Variant but result value type is non-Variant")
    void constructDictionaryShouldRejectImplicitVariantValueMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_partial_operand_mismatch");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdStringType.STRING));

        entry(func).instructions().add(new ConstructDictionaryInsn("dict", "StringName", null));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary value type mismatch"));
    }

    @Test
    @DisplayName("generate should inject typed construct instructions into __prepare__ for Array and Dictionary variables")
    void generateShouldInjectConstructInstructionsIntoPrepareBlock() {
        var clazz = newTestClass();
        var func = newFunction("prepare_inject_constructs");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));
        entry(func).instructions().add(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepare = func.getBasicBlock("__prepare__");
        assertNotNull(prepare);
        assertEquals("__prepare__", func.getEntryBlockId());

        var hasArrayInsn = prepare.instructions().stream()
                .filter(ConstructArrayInsn.class::isInstance)
                .map(ConstructArrayInsn.class::cast)
                .anyMatch(insn -> "arr".equals(insn.resultId()) && "StringName".equals(insn.className()));
        assertTrue(hasArrayInsn);

        var hasDictionaryInsn = prepare.instructions().stream()
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
        entry(func).instructions().add(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepare = func.getBasicBlock("__prepare__");
        assertNotNull(prepare);
        var hasPackedArrayInsn = prepare.instructions().stream()
                .filter(ConstructArrayInsn.class::isInstance)
                .map(ConstructArrayInsn.class::cast)
                .anyMatch(insn -> "packed".equals(insn.resultId()) && insn.className() == null);
        assertTrue(hasPackedArrayInsn);

        var hasPackedBuiltinInsn = prepare.instructions().stream()
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
        entry(func).instructions().add(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var body = codegen.generateFuncBody(clazz, func);
        assertTrue(body.contains("__prepare__: // __prepare__"));
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
    }

    @Test
    @DisplayName("__prepare__ generated Packed*Array construct instruction should emit packed constructor C call")
    void generatedPreparePackedConstructShouldEmitPackedConstructorCall() {
        var clazz = newTestClass();
        var func = newFunction("prepare_emit_packed_ctor_call");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).instructions().add(new ReturnInsn(null));
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
            entry(func).instructions().add(new ConstructArrayInsn("packed", null));
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
            entry(func).instructions().add(new ReturnInsn(null));
            clazz.addFunction(func);

            var module = new LirModule("test_module", List.of(clazz));
            var codegen = newCodegen(module, List.of(clazz));
            codegen.generate();

            var prepare = func.getBasicBlock("__prepare__");
            assertNotNull(prepare);
            var hasPackedArrayInsn = prepare.instructions().stream()
                    .filter(ConstructArrayInsn.class::isInstance)
                    .map(ConstructArrayInsn.class::cast)
                    .anyMatch(insn -> "packed".equals(insn.resultId()) && insn.className() == null);
            assertTrue(hasPackedArrayInsn, () -> "Missing construct_array injection for " + packedCase.typeName());

            var hasPackedBuiltinInsn = prepare.instructions().stream()
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
            entry(func).instructions().add(new ReturnInsn(null));
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

            var hasConstructArrayInsn = entryBlock.instructions().stream()
                    .filter(ConstructArrayInsn.class::isInstance)
                    .map(ConstructArrayInsn.class::cast)
                    .anyMatch(insn -> insn.className() == null);
            assertTrue(
                    hasConstructArrayInsn,
                    () -> "Field init function should use construct_array for " + propertyCase.packedCase().typeName()
            );

            var hasConstructBuiltinInsn = entryBlock.instructions().stream()
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
        prepare.instructions().add(new ConstructArrayInsn("arr", "String"));
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
        prepare.instructions().add(new ConstructDictionaryInsn("dict", "String", "Variant"));
        func.addBasicBlock(prepare);
        func.setEntryBlockId("__prepare__");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary key type mismatch"));
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

    private void assertPackedArrayClassNameRejected(String className) {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array_with_blank_class_name");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).instructions().add(new ConstructArrayInsn("packed", className));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must not provide class_name"));
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
        var classRegistry = new ClassRegistry(apiWithPackedConstructors());
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
