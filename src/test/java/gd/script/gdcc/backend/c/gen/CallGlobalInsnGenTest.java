package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.GodotBindingProvidedSymbols;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
                "ret",
                "godot_deg_to_rad",
                List.of(new LirInstruction.VariableOperand("deg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("$ret = godot_deg_to_rad($deg);"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should resolve backend-owned Variant writeback helper")
    void callGlobalResolvesVariantWritebackRuntimeHelper() {
        var clazz = newTestClass();
        var func = newFunction("call_variant_writeback_helper");
        func.createAndAddVariable("carrier", GdVariantType.VARIANT);
        func.createAndAddVariable("should_writeback", GdBoolType.BOOL);

        entry(func).appendInstruction(new CallGlobalInsn(
                "should_writeback",
                "gdcc_variant_requires_writeback",
                List.of(new LirInstruction.VariableOperand("carrier"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());

        assertTrue(body.contains("$should_writeback = gdcc_variant_requires_writeback(&$carrier);"), body);
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject unknown utility")
    void callGlobalUnknownUtility() {
        var clazz = newTestClass();
        var func = newFunction("call_missing");

        entry(func).appendInstruction(new CallGlobalInsn(null, "missing_utility", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not found in registry"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject compiler-only init helper because it is intrinsic-only")
    void callGlobalShouldRejectCompilerOnlyInitHelper() {
        var clazz = newTestClass();
        var func = newFunction("call_compiler_only_init_global");
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);

        entry(func).appendInstruction(new CallGlobalInsn(
                null,
                GdccForRangeIterType.C_INIT_HELPER_NAME,
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("not found in registry"), ex.getMessage());
        assertTrue(ex.getMessage().contains(GdccForRangeIterType.C_INIT_HELPER_NAME), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject compiler-only fixed argument")
    void callGlobalShouldRejectCompilerOnlyFixedArgument() {
        var clazz = newTestClass();
        var func = newFunction("call_compiler_only_argument");
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).appendInstruction(new CallGlobalInsn(
                "ret",
                "deg_to_rad",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("compiler-only type leaked into call_global argument #1 variable 'iter'"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject resultId for void utility")
    void callGlobalVoidUtilityWithResultId() {
        var clazz = newTestClass();
        var func = newFunction("call_print_with_result");
        func.createAndAddVariable("v1", GdVariantType.VARIANT);
        func.createAndAddVariable("ret", GdFloatType.FLOAT);

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(null, "print", List.of()));
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

        entry(func).appendInstruction(new CallGlobalInsn(
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

    @Test
    @DisplayName("CALL_GLOBAL should route synthetic language functions to gdcc_* helpers")
    void callGlobalSyntheticLanguageFunctionsRouteToGdccHelpers() {
        var clazz = newTestClass();

        var lenFunc = newFunction("call_len");
        lenFunc.createAndAddVariable("v", GdVariantType.VARIANT);
        lenFunc.createAndAddVariable("n", GdIntType.INT);
        entry(lenFunc).appendInstruction(new CallGlobalInsn(
                "n",
                "len",
                List.of(new LirInstruction.VariableOperand("v"))
        ));
        clazz.addFunction(lenFunc);

        var charFunc = newFunction("call_char");
        charFunc.createAndAddVariable("code", GdIntType.INT);
        charFunc.createAndAddVariable("s", GdStringType.STRING);
        entry(charFunc).appendInstruction(new CallGlobalInsn(
                "s",
                "char",
                List.of(new LirInstruction.VariableOperand("code"))
        ));
        clazz.addFunction(charFunc);

        var ordFunc = newFunction("call_ord");
        ordFunc.createAndAddVariable("text", GdStringType.STRING);
        ordFunc.createAndAddVariable("o", GdIntType.INT);
        entry(ordFunc).appendInstruction(new CallGlobalInsn(
                "o",
                "ord",
                List.of(new LirInstruction.VariableOperand("text"))
        ));
        clazz.addFunction(ordFunc);

        var lenBody = generateBody(clazz, lenFunc, utilityApi());
        var charBody = generateBody(clazz, charFunc, utilityApi());
        var ordBody = generateBody(clazz, ordFunc, utilityApi());

        // Synthetic language functions must reach the gdcc_* helpers, never a godot_* wrapper.
        assertTrue(lenBody.contains("$n = gdcc_len(&$v);"), lenBody);
        assertFalse(lenBody.contains("godot_len"), lenBody);
        assertTrue(charBody.contains("$s = gdcc_char($code);"), charBody);
        assertFalse(charBody.contains("godot_char"), charBody);
        assertTrue(ordBody.contains("$o = gdcc_ord(&$text);"), ordBody);
        assertFalse(ordBody.contains("godot_ord"), ordBody);
    }

    @Test
    @DisplayName("CALL_GLOBAL should resolve godot_-prefixed synthetic names to gdcc_* helpers")
    void callGlobalPrefixedSyntheticNameStillRoutesToGdccHelper() {
        var clazz = newTestClass();
        var func = newFunction("call_godot_len");
        func.createAndAddVariable("v", GdVariantType.VARIANT);
        func.createAndAddVariable("n", GdIntType.INT);

        entry(func).appendInstruction(new CallGlobalInsn(
                "n",
                "godot_len",
                List.of(new LirInstruction.VariableOperand("v"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("$n = gdcc_len(&$v);"), body);
        assertFalse(body.contains("godot_len("), body);
    }

    @Test
    @DisplayName("CALL_GLOBAL should allow discarding synthetic language function return")
    void callGlobalSyntheticLanguageFunctionDiscardReturn() {
        var clazz = newTestClass();
        var func = newFunction("call_len_without_result");
        func.createAndAddVariable("v", GdVariantType.VARIANT);

        entry(func).appendInstruction(new CallGlobalInsn(
                null,
                "len",
                List.of(new LirInstruction.VariableOperand("v"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("gdcc_len(&$v);"), body);
        assertFalse(body.contains("= gdcc_len"), "Discard path should not emit assignment");
    }

    @Test
    @DisplayName("CALL_GLOBAL should reject the registered `load` language function end-to-end")
    void callGlobalLoadFailsFastEndToEnd() {
        // `load` IS registered in the synthetic table (frontend argument checking), but frontend
        // lowering must have rewritten it to the ResourceLoader singleton call pair.
        // A `call_global "load"` reaching the backend therefore indicates a lowering gap and must
        // hit the unmapped-name fail-fast, not the plain unknown-utility path.
        var clazz = newTestClass();
        var func = newFunction("call_load");
        func.createAndAddVariable("v", GdVariantType.VARIANT);

        entry(func).appendInstruction(new CallGlobalInsn(
                null,
                "load",
                List.of(new LirInstruction.VariableOperand("v"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, utilityApi()));
        assertTrue(ex.getMessage().contains("no gdcc_* runtime helper mapping"), ex.getMessage());
        assertTrue(ex.getMessage().contains("load"), ex.getMessage());
    }

    @Test
    @DisplayName("gdcc_* route table should fail fast for language functions without a mapping")
    void gdscriptLanguageFunctionCNameMappingFailsFastWhenMissing() {
        // Per-name contract: `load` is registered but never enters the route table (frontend
        // rewrites it to a ResourceLoader singleton call); it must fail fast instead of
        // silently falling back to a nonexistent `godot_*` wrapper.
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> CGenHelper.requireGdScriptLanguageFunctionCName("load")
        );
        assertTrue(ex.getMessage().contains("load"), ex.getMessage());
        // Sanity: every currently registered language function resolves to its helper.
        assertEquals("gdcc_len", CGenHelper.requireGdScriptLanguageFunctionCName("len"));
        assertEquals("gdcc_char", CGenHelper.requireGdScriptLanguageFunctionCName("char"));
        assertEquals("gdcc_ord", CGenHelper.requireGdScriptLanguageFunctionCName("ord"));
        assertEquals("gdcc_range", CGenHelper.requireGdScriptLanguageFunctionCName("range"));
        assertEquals("gdcc_is_instance_of_global", CGenHelper.requireGdScriptLanguageFunctionCName("is_instance_of"));
    }

    @Test
    @DisplayName("CALL_GLOBAL should route range to gdcc_range with the whole argv tail")
    void callGlobalRangeRoutesToGdccRange() {
        var clazz = newTestClass();
        var func = newFunction("call_range");
        func.createAndAddVariable("a", GdVariantType.VARIANT);
        func.createAndAddVariable("b", GdVariantType.VARIANT);
        func.createAndAddVariable("c", GdVariantType.VARIANT);
        func.createAndAddVariable("r", new GdArrayType(GdVariantType.VARIANT));

        entry(func).appendInstruction(new CallGlobalInsn(
                "r",
                "range",
                List.of(
                        new LirInstruction.VariableOperand("a"),
                        new LirInstruction.VariableOperand("b"),
                        new LirInstruction.VariableOperand("c")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        // `range` has zero fixed parameters, so every argument travels through the vararg argv.
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_0[] = { &$a, &$b, &$c };"), body);
        assertTrue(body.contains("gdcc_range(__gdcc_tmp_argv_0, (godot_int)3)"), body);
        assertFalse(body.contains("godot_range"), body);
    }

    @Test
    @DisplayName("CALL_GLOBAL discard of range must destroy the OWNED Array return")
    void callGlobalRangeDiscardDestroysOwnedArray() {
        var clazz = newTestClass();
        var func = newFunction("call_range_discard");
        func.createAndAddVariable("a", GdVariantType.VARIANT);

        entry(func).appendInstruction(new CallGlobalInsn(
                null,
                "range",
                List.of(new LirInstruction.VariableOperand("a"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        // `gdcc_range` returns an OWNED destroyable Array; discarding must still destroy it
        // (the discard temp index is shared with other temp families, so anchor on the prefix).
        assertTrue(body.contains("godot_Array __gdcc_tmp_discard_"), body);
        assertTrue(body.contains(" = gdcc_range("), body);
        assertTrue(body.contains("godot_Array_destroy(&__gdcc_tmp_discard_"), body);
    }

    @Test
    @DisplayName("CALL_GLOBAL should route is_instance_of to the global-only helper")
    void callGlobalIsInstanceOfRoutesToGlobalHelper() {
        var clazz = newTestClass();
        var func = newFunction("call_is_instance_of");
        func.createAndAddVariable("v", GdVariantType.VARIANT);
        func.createAndAddVariable("t", GdVariantType.VARIANT);
        func.createAndAddVariable("ok", GdBoolType.BOOL);

        entry(func).appendInstruction(new CallGlobalInsn(
                "ok",
                "is_instance_of",
                List.of(
                        new LirInstruction.VariableOperand("v"),
                        new LirInstruction.VariableOperand("t")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, utilityApi());
        assertTrue(body.contains("$ok = gdcc_is_instance_of_global(&$v, &$t);"), body);
        assertFalse(body.contains("godot_is_instance_of"), body);
        // Hard boundary: the global function must never reuse the `x is T` Object helpers.
        assertFalse(body.contains("gdcc_is_instance_of_object"), body);
        assertFalse(body.contains("gdcc_is_instance_of_typed"), body);
    }

    @Test
    @DisplayName("Synthetic language functions must not leak into godot_* provided symbols")
    void syntheticLanguageFunctionsStayOutOfProvidedSymbols() throws IOException {
        // The binding/provided-symbol pipeline consumes the raw extension table only; a leak here
        // would make the scanner accept (and callers emit) nonexistent `godot_len`-style wrappers.
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var providedSymbols = GodotBindingProvidedSymbols.forRegistry(registry);
        for (var name : List.of("godot_len", "godot_char", "godot_ord")) {
            assertFalse(providedSymbols.contains(name), "provided symbols must not contain " + name);
        }
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

        entry(func).appendInstruction(new CallGlobalInsn(
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
