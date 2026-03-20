package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGlobalInsnGenTest {
    @Test
    @DisplayName("CALL_GLOBAL should assign non-void utility return")
    void callGlobalAssignNonVoidUtility() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("$ret = godot_deg_to_rad($deg);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should complete omitted default arguments for utility call")
    void callGlobalShouldCompleteDefaultArgument() {
        var clazz = newTestClass();
        var func = newFunction("call_utility_with_default");
        func.createAndAddVariable("required", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "utility_with_default",
                List.of(new LirInstruction.VariableOperand("required"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("godot_int __gdcc_tmp_default_arg_2_0;"));
        assertTrue(body.contains("__gdcc_tmp_default_arg_2_0 = 7;"));
        assertTrue(body.contains("godot_utility_with_default($required, __gdcc_tmp_default_arg_2_0);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should complete default String arguments using static literal")
    void callGlobalShouldCompleteDefaultStringArgument() {
        var clazz = newTestClass();
        var func = newFunction("call_utility_with_default_string");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "utility_with_default_string",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("godot_String __gdcc_tmp_default_arg_1_0;"));
        assertTrue(body.contains("godot_utility_with_default_string(&__gdcc_tmp_default_arg_1_0);"));
        assertTrue(body.contains("godot_String_destroy(&__gdcc_tmp_default_arg_1_0);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should materialize typed Array constructor defaults")
    void callGlobalShouldCompleteTypedArrayConstructorDefault() {
        var clazz = newTestClass();
        var func = newFunction("call_utility_with_default_typed_array");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "utility_with_default_typed_array",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.matches("(?s).*\\b__gdcc_tmp_default_arg_1_\\d+;.*"), body);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant("));
        assertTrue(body.matches("(?s).*godot_utility_with_default_typed_array\\(&__gdcc_tmp_default_arg_1_\\d+\\);.*"));
        assertTrue(body.matches("(?s).*godot_Array_destroy\\(&__gdcc_tmp_default_arg_1_\\d+\\);.*"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should materialize typed Dictionary constructor defaults")
    void callGlobalShouldCompleteTypedDictionaryConstructorDefault() {
        var clazz = newTestClass();
        var func = newFunction("call_utility_with_default_typed_dictionary");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "utility_with_default_typed_dictionary",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.matches("(?s).*\\b__gdcc_tmp_default_arg_1_\\d+;.*"), body);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant("));
        assertTrue(body.matches("(?s).*godot_utility_with_default_typed_dictionary\\(&__gdcc_tmp_default_arg_1_\\d+\\);.*"));
        assertTrue(body.matches("(?s).*godot_Dictionary_destroy\\(&__gdcc_tmp_default_arg_1_\\d+\\);.*"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should fail when required fixed argument is omitted")
    void callGlobalShouldFailWhenRequiredArgumentIsMissing() {
        var clazz = newTestClass();
        var func = newFunction("call_utility_with_default_missing_required");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "utility_with_default",
                List.of()
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("missing required parameter #1"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should render vararg utility with NULL argv when no extras")
    void callGlobalVarargNoExtraUsesNullArgv() {
        var clazz = newTestClass();
        var func = newFunction("call_print_one");
        func.createAndAddVariable("v1", GdVariantType.VARIANT);

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "print",
                List.of(new LirInstruction.VariableOperand("v1"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("godot_print(&$v1, NULL, (godot_int)0);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should render vararg argv array when extras exist")
    void callGlobalVarargWithExtrasUsesArgvArray() {
        var clazz = newTestClass();
        var func = newFunction("call_print_many");
        func.createAndAddVariable("v1", GdVariantType.VARIANT);
        func.createAndAddVariable("v2", GdVariantType.VARIANT);
        func.createAndAddVariable("v3", GdVariantType.VARIANT);

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "print",
                List.of(
                        new LirInstruction.VariableOperand("v1"),
                        new LirInstruction.VariableOperand("v2"),
                        new LirInstruction.VariableOperand("v3")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_0[] = { &$v2, &$v3 };"));
        assertTrue(body.contains("godot_print(&$v1, __gdcc_tmp_argv_0, (godot_int)2);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should accept prefixed utility name")
    void callGlobalPrefixedName() {
        var clazz = newTestClass();
        var func = newFunction("call_prefixed_print");
        func.createAndAddVariable("v1", GdVariantType.VARIANT);

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "godot_print",
                List.of(new LirInstruction.VariableOperand("v1"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("godot_print(&$v1, NULL, (godot_int)0);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should accept prefixed non-void utility name")
    void callGlobalPrefixedNonVoidName() {
        var clazz = newTestClass();
        var func = newFunction("call_prefixed_deg_to_rad");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "godot_deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("$ret = godot_deg_to_rad($deg);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject unknown utility")
    void callGlobalUnknownUtility() {
        var clazz = newTestClass();
        var func = newFunction("call_missing");

        entry(func).instructions().add(new CallGlobalInsn(null, "missing_utility", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not found in registry"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject resultId for void utility")
    void callGlobalVoidUtilityWithResultId() {
        var clazz = newTestClass();
        var func = newFunction("call_print_with_result");
        func.createAndAddVariable("v1", GdVariantType.VARIANT);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "print",
                List.of(new LirInstruction.VariableOperand("v1"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("has no return value"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should allow discarding non-void utility return")
    void callGlobalNonVoidUtilityMissingResultId() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad_without_result");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("godot_deg_to_rad($deg);"));
        assertFalse(body.contains("$ret ="), "Discard path should not emit assignment");
    }

    @Test
    @DisplayName("CALL_GLOBAL discard of destroyable return should clean up temporary value")
    void callGlobalDiscardDestroyableReturnShouldCleanup() {
        var clazz = newTestClass();
        var func = newFunction("call_make_string_without_result");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "make_string",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("godot_String __gdcc_tmp_discard_0 = godot_make_string();"));
        assertTrue(body.contains("godot_String_destroy(&__gdcc_tmp_discard_0);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject ref result variable")
    void callGlobalResultRefVariable() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad_ref_result");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);
        func.createAndAddRefVariable("ret", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject incompatible result type")
    void callGlobalResultTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad_wrong_type");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);
        func.createAndAddVariable("ret", GdStringType.STRING);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("incompatible type"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject missing argument variable")
    void callGlobalMissingArgumentVariable() {
        var clazz = newTestClass();
        var func = newFunction("call_print_missing_arg_var");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "print",
                List.of(new LirInstruction.VariableOperand("missing"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Argument variable ID 'missing' not found in function"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject non-variable operands")
    void callGlobalNonVariableOperand() {
        var clazz = newTestClass();
        var func = newFunction("call_print_non_var_operand");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "print",
                List.of(new LirInstruction.StringOperand("not_a_var"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("must be a variable operand"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject too many args for non-vararg utility")
    void callGlobalTooManyArgsForNonVarargUtility() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad_too_many");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);
        func.createAndAddVariable("extra", GdFloatType.FLOAT);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(
                        new LirInstruction.VariableOperand("deg"),
                        new LirInstruction.VariableOperand("extra")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Too many arguments for utility"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject too few args for vararg utility fixed parameters")
    void callGlobalTooFewArgsForVarargFixedParameters() {
        var clazz = newTestClass();
        var func = newFunction("call_print_too_few");

        entry(func).instructions().add(new CallGlobalInsn(null, "print", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Too few arguments for utility"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject fixed argument type mismatch")
    void callGlobalFixedArgumentTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad_wrong_arg_type");
        func.createAndAddVariable("deg", GdStringType.STRING);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Cannot assign value of type 'String'"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject non-variant vararg extras")
    void callGlobalVarargExtraMustBeVariant() {
        var clazz = newTestClass();
        var func = newFunction("call_print_bad_extra_type");
        func.createAndAddVariable("v1", GdVariantType.VARIANT);
        func.createAndAddVariable("s1", GdStringType.STRING);

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "print",
                List.of(
                        new LirInstruction.VariableOperand("v1"),
                        new LirInstruction.VariableOperand("s1")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("must be Variant"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject missing result variable")
    void callGlobalMissingResultVariable() {
        var clazz = newTestClass();
        var func = newFunction("call_deg_to_rad_missing_result_var");
        func.createAndAddVariable("deg", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Result variable ID 'ret' not found in function"));
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

    private String generateBody(LirClassDef clazz, LirFunctionDef func, ExtensionAPI api) {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, api, List.of(clazz));
        return codegen.generateFuncBody(clazz, func);
    }

    private CCodegen newCodegen(LirModule module, ExtensionAPI api, List<LirClassDef> gdccClasses) {
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

    private ExtensionAPI utilityApi() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionUtilityFunction(
                                "print",
                                null,
                                "general",
                                true,
                                0,
                                List.of(new ExtensionFunctionArgument("arg1", "Variant", null, null))
                        ),
                        new ExtensionUtilityFunction(
                                "deg_to_rad",
                                "float",
                                "math",
                                false,
                                2140049587,
                                List.of(new ExtensionFunctionArgument("deg", "float", null, null))
                        ),
                        new ExtensionUtilityFunction(
                                "make_string",
                                "String",
                                "test",
                                false,
                                0,
                                List.of()
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default",
                                null,
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("required", "float", null, null),
                                        new ExtensionFunctionArgument("optional", "int", "7", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default_string",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("text", "String", "\"hello\"", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default_typed_array",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument("items", "Array[StringName]", "Array[StringName]([])", null)
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default_typed_dictionary",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument(
                                                "mapping",
                                                "Dictionary[StringName, int]",
                                                "Dictionary[StringName, int]({})",
                                                null
                                        )
                                )
                        ),
                        new ExtensionUtilityFunction(
                                "utility_with_default_typedarray_metadata",
                                "void",
                                "test",
                                false,
                                0,
                                List.of(
                                        new ExtensionFunctionArgument(
                                                "specialization_constants",
                                                "typedarray::RDPipelineSpecializationConstant",
                                                "Array[RDPipelineSpecializationConstant]([])",
                                                null
                                        )
                                )
                        )
                ),
                List.of(),
                List.of(
                        new ExtensionGdClass(
                                "RDPipelineSpecializationConstant",
                                false,
                                true,
                                "RefCounted",
                                "servers",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                List.of(),
                List.of()
        );
    }

    @Test
    @DisplayName("CALL_GLOBAL should normalize typedarray metadata default through registry-aware utility signature parsing")
    void callGlobalShouldCompleteTypedarrayMetadataDefault() {
        var clazz = newTestClass();
        var func = newFunction("call_utility_with_metadata_typedarray_default");

        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "utility_with_default_typedarray_metadata",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.matches("(?s).*\\b__gdcc_tmp_default_arg_1_\\d+;.*"), body);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant("), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"RDPipelineSpecializationConstant\")"), body);
        assertTrue(body.matches("(?s).*godot_utility_with_default_typedarray_metadata\\(&__gdcc_tmp_default_arg_1_\\d+\\);.*"), body);
        assertTrue(body.matches("(?s).*godot_Array_destroy\\(&__gdcc_tmp_default_arg_1_\\d+\\);.*"), body);
    }
}
