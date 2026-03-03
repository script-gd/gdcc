package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
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

class COperatorInsnGenTest {
    @Test
    @DisplayName("primitive compare should use direct C expression when metadata supports")
    void primitiveCompareUsesDirectExpressionWhenMetadataMatches() {
        var body = generateBody(
                primitiveCompareApi(),
                new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = ($left > $right);"), body);
    }

    @Test
    @DisplayName("primitive compare should fail-fast when metadata is missing")
    void primitiveCompareFailsWhenMetadataMissing() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Primitive compare metadata is missing"), ex.getMessage());
    }

    @Test
    @DisplayName("object == should call gdcc_cmp_object helper")
    void objectEqualUsesInstanceIdSpecialization() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_obj", "right_obj"),
                List.of(
                        new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_cmp_object($left_obj, $right_obj);"), body);
    }

    @Test
    @DisplayName("object != should negate gdcc_cmp_object result")
    void objectNotEqualUsesNegatedSpecialization() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "left_obj", "right_obj"),
                List.of(
                        new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("__gdcc_tmp_gdcc_cmp_object_eq_0 = gdcc_cmp_object($left_obj, $right_obj);"), body);
        assertTrue(body.contains("$result = !__gdcc_tmp_gdcc_cmp_object_eq_0;"), body);
    }

    @Test
    @DisplayName("object non-==/!= compare should fail-fast")
    void objectNonEqualityCompareFailsFast() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left_obj", "right_obj"),
                        List.of(
                                new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Object comparison supports only == and !="), ex.getMessage());
    }

    @Test
    @DisplayName("Nil == Nil should emit true")
    void nilEqualNilEmitsTrue() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_nil", "right_nil"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = (true);"), body);
    }

    @Test
    @DisplayName("Nil != Nil should emit false semantics")
    void nilNotEqualNilEmitsFalseSemantics() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "left_nil", "right_nil"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = (!(true));"), body);
    }

    @Test
    @DisplayName("Nil == Object should compare object with NULL")
    void nilEqualObjectUsesNullCompare() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_nil", "obj"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("obj", new GdObjectType("Node"), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = ($obj == NULL);"), body);
    }

    @Test
    @DisplayName("Nil == non-Object should emit false")
    void nilEqualNonObjectEmitsFalse() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_nil", "value"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("value", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = (false);"), body);
    }

    @Test
    @DisplayName("compare result type must be compatible with bool")
    void compareResultTypeMustBeBoolAssignable() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.EQUAL, "left_obj", "right_obj"),
                        List.of(
                                new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Operator result type 'bool' is not assignable"), ex.getMessage());
    }

    @Test
    @DisplayName("result ref variable should fail-fast")
    void resultRefVariableFailsFast() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        primitiveCompareApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, true)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("unary metadata should match with normalized empty rightType and emit evaluator call")
    void unaryMetadataWithEmptyRightTypeEmitsEvaluatorCall() {
        var body = generateBody(
                evaluatorIntApi(),
                new UnaryOpInsn("result", GodotOperator.NEGATE, "operand"),
                List.of(
                        new VariableSpec("operand", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_unary_negate_int_to_int($operand);"), body);
    }

    @Test
    @DisplayName("binary non-compare builtin should emit ptr evaluator call")
    void binaryNonCompareBuiltinEmitsEvaluatorCall() {
        var body = generateBody(
                evaluatorIntApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_in_int_int_to_bool($left, $right);"), body);
    }

    @Test
    @DisplayName("binary metadata lookup should skip malformed entries and keep valid match")
    void binaryMetadataLookupSkipsMalformedEntries() {
        var body = generateBody(
                malformedEvaluatorIntApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_in_int_int_to_bool($left, $right);"), body);
    }

    @Test
    @DisplayName("evaluator path should fail-fast when semantic result type is incompatible")
    void evaluatorPathFailsWhenResultTypeIncompatible() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        evaluatorIntApi(),
                        new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Operator result type 'bool' is not assignable"), ex.getMessage());
    }

    @Test
    @DisplayName("operators should fail-fast when original-order metadata is missing")
    void missingOriginalMetadataFailsWithoutSwapFallback() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        dualFallbackApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("non-commutative operators should fail-fast without swap fallback")
    void nonRegisteredOperatorDoesNotSwapFallback() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        nonSwappableFallbackCandidateApi(),
                        new BinaryOpInsn("result", GodotOperator.SUBTRACT, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, SUBTRACT, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("IN should fail-fast when original-order metadata is missing")
    void inOperatorShouldNotUseSwapFallback() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        inFallbackCandidateApi(),
                        new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, IN, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("comparison operators should fail-fast when original-order metadata is missing")
    void registeredFallbackOperatorFailsWhenBothDirectionsMiss() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("binary op should use variant_evaluate when any operand is Variant")
    void variantOperandForcesVariantEvaluatePath() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_ADD"), body);
        assertTrue(body.contains("&$left"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($right)"), body);
        assertTrue(body.matches("(?s).*godot_Variant __gdcc_tmp_op_eval_result_\\d+;.*"), body);
        assertFalse(body.matches("(?s).*godot_Variant __gdcc_tmp_op_eval_result_\\d+\\s*=.*"), body);
        assertTrue(body.contains("$result = godot_new_Variant_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
        assertFalse(
                body.matches("(?s).*godot_Variant __gdcc_tmp_variant_\\d+ = godot_new_Variant_with_Variant\\(&__gdcc_tmp_op_eval_result_\\d+\\);.*"),
                body
        );
        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR(\"godot_variant_evaluate failed for operator 'ADD'\""), body);
        assertTrue(body.contains("if (!__gdcc_tmp_op_eval_valid_"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertFalse(body.contains("gdcc_eval_binary_"), body);
    }

    @Test
    @DisplayName("variant_evaluate path should unpack to non-Variant builtin result with runtime type check")
    void variantEvaluatePathUnpacksToBuiltinResultWithRuntimeTypeCheck() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_IN"), body);
        assertTrue(body.contains("gdcc_check_variant_type_builtin(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_BOOL"), body);
        assertTrue(body.contains("$result = godot_new_bool_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("variant_evaluate type check failed for operator 'IN': expected bool"), body);
    }

    @Test
    @DisplayName("variant_evaluate path should allow engine object subclass check before unpack")
    void variantEvaluatePathSupportsEngineObjectSubtypeCheck() {
        var body = generateBody(
                engineObjectApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdVariantType.VARIANT, false),
                        new VariableSpec("result", new GdObjectType("Node"), false)
                )
        );

        assertTrue(body.contains("gdcc_check_variant_type_object(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
        assertTrue(body.contains(", false) || gdcc_check_variant_type_object("), body);
        assertTrue(body.contains(", true))"), body);
        assertTrue(body.contains("$result = (godot_Node*)godot_new_Object_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
    }

    @Test
    @DisplayName("variant_evaluate path should allow gdcc object subclass check before unpack")
    void variantEvaluatePathSupportsGdccObjectSubtypeCheck() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdVariantType.VARIANT, false),
                        new VariableSpec("result", new GdObjectType("Worker"), false)
                )
        );

        assertTrue(body.contains("gdcc_check_variant_type_object(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Worker\")"), body);
        assertTrue(body.contains(", false) || gdcc_check_variant_type_object("), body);
        assertTrue(body.contains(", true))"), body);
        assertTrue(body.contains("$result = (Worker*)godot_new_gdcc_Object_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
    }

    @Test
    @DisplayName("variant operand should still use variant_evaluate even when metadata exists")
    void variantOperandStillUsesVariantEvaluateWhenMetadataExists() {
        var body = generateBody(
                evaluatorIntApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_IN"), body);
        assertFalse(body.contains("gdcc_eval_binary_in_int_int_to_bool"), body);
    }

    @Test
    @DisplayName("variant_evaluate failure should return function default value for non-void function")
    void variantEvaluateFailureReturnsFunctionDefaultValue() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR(\"godot_variant_evaluate failed for operator 'ADD'\""), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("POWER(float,int) should use pow fast path")
    void powerFloatIntUsesPowFastPath() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.POWER, "left", "right"),
                List.of(
                        new VariableSpec("left", GdFloatType.FLOAT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdFloatType.FLOAT, false)
                )
        );

        assertTrue(body.contains("$result = pow($left, $right);"), body);
    }

    @Test
    @DisplayName("POWER(int,int) should use pow_int fast path")
    void powerIntIntUsesPowIntFastPath() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.POWER, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("if (false) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'POWER': no guard violation"), body);
        assertTrue(body.contains("$result = pow_int($left, $right);"), body);
    }

    @Test
    @DisplayName("MODULE(float,float) should hit primitive fast path with fmod")
    void moduleFloatFloatUsesFmodFastPath() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdFloatType.FLOAT, false),
                        new VariableSpec("right", GdFloatType.FLOAT, false),
                        new VariableSpec("result", GdFloatType.FLOAT, false)
                )
        );

        assertTrue(body.contains("if (gdcc_float_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'MODULE': floating modulo by zero"), body);
        assertTrue(body.contains("$result = fmod($left, $right);"), body);
    }

    @Test
    @DisplayName("ADD(int,int) fast path should emit no-op guard without overflow check")
    void addIntIntFastPathEmitsNoopGuardWithoutOverflowCheck() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("if (false) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'ADD': no guard violation"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left + $right);"), body);
    }

    @Test
    @DisplayName("DIVIDE(int,int) fast path should emit zero-divisor guard and return function default value")
    void divideIntIntFastPathEmitsZeroDivisorGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.DIVIDE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("if (gdcc_int_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'DIVIDE': integer division by zero"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left / $right);"), body);
    }

    @Test
    @DisplayName("MODULE(int,int) fast path should emit zero-divisor guard and return function default value")
    void moduleIntIntFastPathEmitsZeroDivisorGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("if (gdcc_int_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'MODULE': integer modulo by zero"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left % $right);"), body);
    }

    @Test
    @DisplayName("SHIFT_LEFT(int,int) fast path should emit invalid-shift guard and return function default value")
    void shiftLeftFastPathEmitsInvalidShiftGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.SHIFT_LEFT, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("if (gdcc_int_shift_left_invalid($left, $right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'SHIFT_LEFT': invalid shift amount or negative left operand"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left << $right);"), body);
    }

    @Test
    @DisplayName("SHIFT_RIGHT(int,int) fast path should emit invalid-shift guard and return function default value")
    void shiftRightFastPathEmitsInvalidShiftGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.SHIFT_RIGHT, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("if (gdcc_int_shift_right_invalid($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'SHIFT_RIGHT': invalid shift amount"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left >> $right);"), body);
    }

    @Test
    @DisplayName("primitive fast path should still fail-fast when metadata is missing")
    void primitiveFastPathFailsWhenMetadataMissing() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.POWER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Primitive fast path metadata is missing"), ex.getMessage());
    }

    @Test
    @DisplayName("XOR(int,int) should use logical xor semantics instead of bit xor")
    void xorIntIntUsesLogicalXorSemantics() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.XOR, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("if (false) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'XOR': no guard violation"), body);
        assertTrue(body.contains("$result = (($left ? 1 : 0) != ($right ? 1 : 0));"), body);
    }

    @Test
    @DisplayName("DIVIDE(float,float) fast path should emit float zero-divisor guard")
    void divideFloatFloatFastPathEmitsZeroDivisorGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.DIVIDE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdFloatType.FLOAT, false),
                        new VariableSpec("right", GdFloatType.FLOAT, false),
                        new VariableSpec("result", GdFloatType.FLOAT, false)
                ),
                GdBoolType.BOOL
        );

        assertTrue(body.contains("if (gdcc_float_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'DIVIDE': floating division by zero"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left / $right);"), body);
    }

    @Test
    @DisplayName("IN(int, Array) should bypass primitive fast path and resolve by metadata in original order")
    void inIntArrayBypassesPrimitiveFastPathAndUsesEvaluator() {
        var body = generateBody(
                inIntArrayApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_in_int_array_to_bool($left, &$right);"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
        assertFalse(body.contains("pow("), body);
        assertFalse(body.contains("pow_int("), body);
    }

    private @NotNull String generateBody(@NotNull ExtensionAPI api,
                                         @NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs) {
        return generateBody(api, instruction, variableSpecs, GdVoidType.VOID);
    }

    private @NotNull String generateBody(@NotNull ExtensionAPI api,
                                         @NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs,
                                         @NotNull GdType returnType) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false,
                Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("operator_test");
        func.setReturnType(returnType);
        for (var variableSpec : variableSpecs) {
            if (variableSpec.ref()) {
                func.createAndAddRefVariable(variableSpec.id(), variableSpec.type());
            } else {
                func.createAndAddVariable(variableSpec.id(), variableSpec.type());
            }
        }

        var entry = new LirBasicBlock("entry");
        entry.instructions().add(instruction);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("test_module", List.of(workerClass));
        var codegen = newCodegen(api, module, List.of(workerClass));
        return codegen.generateFuncBody(workerClass, func);
    }

    private @NotNull CCodegen newCodegen(@NotNull ExtensionAPI api,
                                         @NotNull LirModule module,
                                         @NotNull List<LirClassDef> gdccClasses) {
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

    private @NotNull ExtensionAPI primitiveCompareApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator(">", "int", "bool"),
                        new ExtensionBuiltinClass.ClassOperator("==", "int", "bool"),
                        new ExtensionBuiltinClass.ClassOperator("!=", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI engineObjectApi() {
        var objectClass = new ExtensionGdClass(
                "Object",
                false,
                true,
                "",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var nodeClass = new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(objectClass, nodeClass),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI evaluatorIntApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("unary-", "", "int"),
                        new ExtensionBuiltinClass.ClassOperator("in", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI malformedEvaluatorIntApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("invalid_op", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("in", "", "bool"),
                        new ExtensionBuiltinClass.ClassOperator("in", "int", ""),
                        new ExtensionBuiltinClass.ClassOperator("in", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI primitiveFastPathApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("+", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("/", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("**", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("%", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("<<", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator(">>", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("xor", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var floatBuiltin = new ExtensionBuiltinClass(
                "float",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("/", "float", "float"),
                        new ExtensionBuiltinClass.ClassOperator("%", "float", "float"),
                        new ExtensionBuiltinClass.ClassOperator("**", "int", "float")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin, floatBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI dualFallbackApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("<", "String", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI nonSwappableFallbackCandidateApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("-", "String", "int")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI inFallbackCandidateApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "String", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI inIntArrayApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "Array", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record VariableSpec(@NotNull String id, @NotNull GdType type, boolean ref) {
    }
}
