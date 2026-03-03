package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// C code generator for `UNARY_OP` and `BINARY_OP`.
///
/// Stage 1 scope:
/// - Validate instruction wiring and variable contracts.
/// - Route to resolver path decision.
/// - Fail fast for all unresolved paths.
///
/// Stage 2 extends this generator with compare specializations.
public final class OperatorInsnGen implements CInsnGen<LirInstruction> {
    private final @NotNull OperatorResolver resolver = new OperatorResolver();

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.UNARY_OP, GdInstruction.BINARY_OP);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        switch (instruction) {
            case UnaryOpInsn unaryOpInsn -> emitUnary(bodyBuilder, unaryOpInsn);
            case BinaryOpInsn binaryOpInsn -> emitBinary(bodyBuilder, binaryOpInsn);
            default -> throw bodyBuilder.invalidInsn(
                    "Unsupported operator instruction type: " + instruction.getClass().getSimpleName());
        }
    }

    private void emitUnary(@NotNull CBodyBuilder bodyBuilder,
                           @NotNull UnaryOpInsn instruction) {
        var resultVar = resolveRequiredResultVariable(bodyBuilder, instruction.resultId());
        var operandVar = resolveOperandVariable(bodyBuilder, instruction.operandId(), "operand");

        var decision = resolver.resolveUnaryPath(bodyBuilder, instruction.op(), operandVar.type());
        if (decision.path() == OperatorResolver.OperatorPath.UNIMPLEMENTED) {
            throwUnimplementedPath(bodyBuilder, "unary", instruction.op().name(), decision.reason(),
                    operandVar.type(), null);
            return;
        }

        if (decision.semanticResultType() == null) {
            throw bodyBuilder.invalidInsn(
                    "Resolved operator path '" + decision.path() + "' is missing semantic result type");
        }
        validateResultCompatibility(bodyBuilder, decision.path(), decision.semanticResultType(), resultVar);

        if (decision.path() == OperatorResolver.OperatorPath.BUILTIN_EVALUATOR) {
            emitUnaryBuiltinEvaluator(
                    bodyBuilder,
                    resultVar,
                    instruction.op(),
                    operandVar,
                    decision.semanticResultType()
            );
        } else {
            throw bodyBuilder.invalidInsn(
                    "Unary operator path '" + decision.path() + "' is not valid in current stage");
        }
    }

    private void emitBinary(@NotNull CBodyBuilder bodyBuilder,
                            @NotNull BinaryOpInsn instruction) {
        var resultVar = resolveRequiredResultVariable(bodyBuilder, instruction.resultId());
        var leftVar = resolveOperandVariable(bodyBuilder, instruction.leftId(), "left");
        var rightVar = resolveOperandVariable(bodyBuilder, instruction.rightId(), "right");

        var decision = resolver.resolveBinaryPath(bodyBuilder, instruction.op(), leftVar.type(), rightVar.type());
        if (decision.path() == OperatorResolver.OperatorPath.UNIMPLEMENTED) {
            throwUnimplementedPath(bodyBuilder, "binary", instruction.op().name(), decision.reason(),
                    leftVar.type(), rightVar.type());
            return;
        }

        if (decision.semanticResultType() == null) {
            throw bodyBuilder.invalidInsn(
                    "Resolved operator path '" + decision.path() + "' is missing semantic result type");
        }
        validateResultCompatibility(bodyBuilder, decision.path(), decision.semanticResultType(), resultVar);

        switch (decision.path()) {
            case PRIMITIVE_FAST_PATH ->
                    emitPrimitiveFastPath(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar, decision.semanticResultType());
            case PRIMITIVE_COMPARISON ->
                    emitPrimitiveComparison(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
            case OBJECT_COMPARISON -> emitObjectComparison(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
            case NIL_COMPARISON -> emitNilComparison(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
            case VARIANT_EVALUATE ->
                    emitBinaryVariantEvaluate(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
            case BUILTIN_EVALUATOR -> emitBinaryBuiltinEvaluator(
                    bodyBuilder,
                    resultVar,
                    instruction.op(),
                    leftVar,
                    rightVar,
                    decision.semanticResultType()
            );
        }
    }

    private void emitPrimitiveFastPath(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull LirVariable resultVar,
                                       @NotNull GodotOperator op,
                                       @NotNull LirVariable leftVar,
                                       @NotNull LirVariable rightVar,
                                       @NotNull GdType semanticResultType) {
        var leftCode = bodyBuilder.valueOfVar(leftVar).generateCode();
        var rightCode = bodyBuilder.valueOfVar(rightVar).generateCode();
        var useFloatMath = isFloatPrimitiveType(leftVar.type()) || isFloatPrimitiveType(rightVar.type());
        emitPrimitiveFastPathPreCheck(bodyBuilder, op, leftVar, rightVar, leftCode, rightCode, useFloatMath);
        var expression = switch (op) {
            case ADD -> "(" + leftCode + " + " + rightCode + ")";
            case SUBTRACT -> "(" + leftCode + " - " + rightCode + ")";
            case MULTIPLY -> "(" + leftCode + " * " + rightCode + ")";
            case DIVIDE -> "(" + leftCode + " / " + rightCode + ")";
            case MODULE -> useFloatMath
                    ? "fmod(" + leftCode + ", " + rightCode + ")"
                    : "(" + leftCode + " % " + rightCode + ")";
            case POWER -> renderPowerFastPathExpr(bodyBuilder, leftVar, rightVar, leftCode, rightCode, useFloatMath);
            case SHIFT_LEFT -> "(" + leftCode + " << " + rightCode + ")";
            case SHIFT_RIGHT -> "(" + leftCode + " >> " + rightCode + ")";
            case BIT_AND -> "(" + leftCode + " & " + rightCode + ")";
            case BIT_OR -> "(" + leftCode + " | " + rightCode + ")";
            case BIT_XOR -> "(" + leftCode + " ^ " + rightCode + ")";
            case AND -> "((" + leftCode + ") && (" + rightCode + "))";
            case OR -> "((" + leftCode + ") || (" + rightCode + "))";
            case XOR -> "((" + leftCode + " ? 1 : 0) != (" + rightCode + " ? 1 : 0))";
            default -> throw bodyBuilder.invalidInsn(
                    "Primitive fast path does not support operator '" + op.name() + "'"
            );
        };
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVar), expression, semanticResultType);
    }

    private void emitPrimitiveFastPathPreCheck(@NotNull CBodyBuilder bodyBuilder,
                                               @NotNull GodotOperator op,
                                               @NotNull LirVariable leftVar,
                                               @NotNull LirVariable rightVar,
                                               @NotNull String leftCode,
                                               @NotNull String rightCode,
                                               boolean useFloatMath) {
        var leftIsInt = leftVar.type() instanceof GdIntType;
        var rightIsInt = rightVar.type() instanceof GdIntType;
        var bothInt = leftIsInt && rightIsInt;

        var guardRule = switch (op) {
            case DIVIDE -> bothInt
                    ? new PrimitiveFastPathGuardRule(
                    "gdcc_int_division_by_zero(" + rightCode + ")",
                    "integer division by zero")
                    : new PrimitiveFastPathGuardRule(
                    "gdcc_float_division_by_zero(" + rightCode + ")",
                    "floating division by zero");
            case MODULE -> bothInt
                    ? new PrimitiveFastPathGuardRule(
                    "gdcc_int_division_by_zero(" + rightCode + ")",
                    "integer modulo by zero")
                    : new PrimitiveFastPathGuardRule(
                    "gdcc_float_division_by_zero(" + rightCode + ")",
                    "floating modulo by zero");
            case POWER -> PrimitiveFastPathGuardRule.NOOP;
            case SHIFT_LEFT -> bothInt
                    ? new PrimitiveFastPathGuardRule(
                    "gdcc_int_shift_left_invalid(" + leftCode + ", " + rightCode + ")",
                    "invalid shift amount or negative left operand")
                    : PrimitiveFastPathGuardRule.NOOP;
            case SHIFT_RIGHT -> bothInt
                    ? new PrimitiveFastPathGuardRule(
                    "gdcc_int_shift_right_invalid(" + rightCode + ")",
                    "invalid shift amount")
                    : PrimitiveFastPathGuardRule.NOOP;
            default -> PrimitiveFastPathGuardRule.NOOP;
        };
        if (!useFloatMath && (op == GodotOperator.DIVIDE || op == GodotOperator.MODULE) && !bothInt) {
            throw bodyBuilder.invalidInsn(
                    "Primitive fast path expected integer operands for '" + op.name() + "', but got '" +
                            leftVar.type().getTypeName() + "' and '" + rightVar.type().getTypeName() + "'"
            );
        }
        emitPrimitiveFastPathGuardFailure(bodyBuilder, guardRule.invalidConditionExpr(), op, guardRule.reason());
    }

    private void emitPrimitiveFastPathGuardFailure(@NotNull CBodyBuilder bodyBuilder,
                                                   @NotNull String invalidConditionExpr,
                                                   @NotNull GodotOperator op,
                                                   @NotNull String reason) {
        bodyBuilder.appendLine("if (" + invalidConditionExpr + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"Primitive fast path guard failed for operator '" +
                        op.name() + "': " + reason + "\", __func__, __FILE__, __LINE__);"
        );
        emitPrimitiveFastPathFailureReturn(bodyBuilder);
        bodyBuilder.appendLine("}");
    }

    private void emitPrimitiveFastPathFailureReturn(@NotNull CBodyBuilder bodyBuilder) {
        var returnType = bodyBuilder.func().getReturnType();
        if (returnType instanceof GdVoidType) {
            bodyBuilder.returnVoid();
            return;
        }
        var defaultExpr = CBodyBuilder.renderDefaultValueExpr(returnType);
        bodyBuilder.returnValue(bodyBuilder.valueOfExpr(defaultExpr, returnType));
    }

    private @NotNull String renderPowerFastPathExpr(@NotNull CBodyBuilder bodyBuilder,
                                                    @NotNull LirVariable leftVar,
                                                    @NotNull LirVariable rightVar,
                                                    @NotNull String leftCode,
                                                    @NotNull String rightCode,
                                                    boolean useFloatMath) {
        if (useFloatMath) {
            return "pow(" + leftCode + ", " + rightCode + ")";
        }
        var leftIsInt = leftVar.type() instanceof GdIntType;
        var rightIsInt = rightVar.type() instanceof GdIntType;
        if (leftIsInt && rightIsInt) {
            return "pow_int(" + leftCode + ", " + rightCode + ")";
        }
        throw bodyBuilder.invalidInsn(
                "POWER fast path cannot decide between pow and pow_int for operand types '" +
                        leftVar.type().getTypeName() + "' and '" + rightVar.type().getTypeName() + "'"
        );
    }

    private boolean isFloatPrimitiveType(@NotNull GdType type) {
        return type instanceof GdFloatType;
    }

    private void emitUnaryBuiltinEvaluator(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull LirVariable resultVar,
                                           @NotNull GodotOperator op,
                                           @NotNull LirVariable operandVar,
                                           @NotNull GdType semanticResultType) {
        var helperFunctionName = resolver.renderUnaryEvaluatorHelperName(op, operandVar.type(), semanticResultType);
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                helperFunctionName,
                semanticResultType,
                List.of(bodyBuilder.valueOfVar(operandVar))
        );
    }

    private void emitBinaryBuiltinEvaluator(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull LirVariable resultVar,
                                            @NotNull GodotOperator op,
                                            @NotNull LirVariable leftVar,
                                            @NotNull LirVariable rightVar,
                                            @NotNull GdType semanticResultType) {
        var helperFunctionName = resolver.renderBinaryEvaluatorHelperName(
                op,
                leftVar.type(),
                rightVar.type(),
                semanticResultType
        );
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                helperFunctionName,
                semanticResultType,
                List.of(bodyBuilder.valueOfVar(leftVar), bodyBuilder.valueOfVar(rightVar))
        );
    }

    private void emitBinaryVariantEvaluate(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull LirVariable resultVar,
                                           @NotNull GodotOperator op,
                                           @NotNull LirVariable leftVar,
                                           @NotNull LirVariable rightVar) {
        var leftOperand = materializeVariantOperand(bodyBuilder, leftVar, "left");
        var rightOperand = materializeVariantOperand(bodyBuilder, rightVar, "right");

        var resultVariant = bodyBuilder.newTempVariable("op_eval_result", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(resultVariant);
        var validFlag = bodyBuilder.newTempVariable("op_eval_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);
        bodyBuilder.appendLine(
                "godot_variant_evaluate(" +
                        resolver.resolveVariantOperatorEnumLiteral(op) +
                        ", &" + leftOperand.variantValue().generateCode() +
                        ", &" + rightOperand.variantValue().generateCode() +
                        ", &" + resultVariant.name() +
                        ", &" + validFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"godot_variant_evaluate failed for operator '" + op.name() +
                        "'\", __func__, __FILE__, __LINE__);"
        );
        emitVariantEvaluateFailureReturn(bodyBuilder, resultVariant, leftOperand, rightOperand);
        bodyBuilder.appendLine("}");
        resultVariant.setInitialized(true);

        if (resultVar.type() instanceof GdVariantType) {
            // Variant slot write must avoid "copy temp -> plain assignment -> destroy temp".
            // Use constructor call assignment directly so target owns the copied value safely.
            bodyBuilder.callAssign(
                    bodyBuilder.targetOfVar(resultVar),
                    "godot_new_Variant_with_Variant",
                    GdVariantType.VARIANT,
                    List.of(resultVariant)
            );
        } else {
            emitVariantUnpackTypeCheck(
                    bodyBuilder,
                    op,
                    resultVariant,
                    resultVar.type(),
                    leftOperand,
                    rightOperand
            );
            var unpackFunctionName = bodyBuilder.helper().renderUnpackFunctionName(resultVar.type());
            bodyBuilder.callAssign(
                    bodyBuilder.targetOfVar(resultVar),
                    unpackFunctionName,
                    resultVar.type(),
                    List.of(resultVariant)
            );
        }

        bodyBuilder.destroyTempVar(resultVariant);
        if (rightOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(rightOperand.tempVar());
        }
        if (leftOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(leftOperand.tempVar());
        }
    }

    private void emitVariantUnpackTypeCheck(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull GodotOperator op,
                                            @NotNull CBodyBuilder.TempVar resultVariant,
                                            @NotNull GdType targetType,
                                            @NotNull VariantOperand leftOperand,
                                            @NotNull VariantOperand rightOperand) {
        var typeCheckExpr = renderVariantUnpackTypeCheckExpr(bodyBuilder, resultVariant, targetType);
        bodyBuilder.appendLine("if (!(" + typeCheckExpr + ")) {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_evaluate type check failed for operator '" + op.name() +
                        "': expected " + targetType.getTypeName() + "\", __func__, __FILE__, __LINE__);"
        );
        emitVariantEvaluateTypeCheckFailureReturn(bodyBuilder, resultVariant, leftOperand, rightOperand);
        bodyBuilder.appendLine("}");
    }

    private @NotNull String renderVariantUnpackTypeCheckExpr(@NotNull CBodyBuilder bodyBuilder,
                                                             @NotNull CBodyBuilder.TempVar resultVariant,
                                                             @NotNull GdType targetType) {
        if (targetType instanceof GdObjectType objectType) {
            return renderVariantObjectTypeCheckExpr(bodyBuilder, resultVariant, objectType);
        }
        var expectedTypeLiteral = resolver.resolveVariantTypeEnumLiteral(bodyBuilder, targetType);
        return "gdcc_check_variant_type_builtin(&" + resultVariant.name() + ", " + expectedTypeLiteral + ")";
    }

    private @NotNull String renderVariantObjectTypeCheckExpr(@NotNull CBodyBuilder bodyBuilder,
                                                             @NotNull CBodyBuilder.TempVar resultVariant,
                                                             @NotNull GdObjectType targetObjectType) {
        var expectedClassLiteral = CBodyBuilder.renderStaticStringNameLiteral(targetObjectType.getTypeName());
        var exactMatchExpr = "gdcc_check_variant_type_object(&" + resultVariant.name() + ", " +
                expectedClassLiteral + ", false)";

        var isEngineType = targetObjectType.checkEngineType(bodyBuilder.classRegistry());
        if (isEngineType) {
            var subclassMatchExpr = "gdcc_check_variant_type_object(&" + resultVariant.name() + ", " +
                    expectedClassLiteral + ", true)";
            return "(" + exactMatchExpr + " || " + subclassMatchExpr + ")";
        }
        return exactMatchExpr;
    }

    private void emitVariantEvaluateTypeCheckFailureReturn(@NotNull CBodyBuilder bodyBuilder,
                                                           @NotNull CBodyBuilder.TempVar resultVariant,
                                                           @NotNull VariantOperand leftOperand,
                                                           @NotNull VariantOperand rightOperand) {
        bodyBuilder.destroyTempVar(resultVariant);
        if (rightOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(rightOperand.tempVar());
        }
        if (leftOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(leftOperand.tempVar());
        }

        var returnType = bodyBuilder.func().getReturnType();
        if (returnType instanceof GdVoidType) {
            bodyBuilder.returnVoid();
            return;
        }
        var defaultExpr = CBodyBuilder.renderDefaultValueExpr(returnType);
        bodyBuilder.returnValue(bodyBuilder.valueOfExpr(defaultExpr, returnType));
    }

    private @NotNull VariantOperand materializeVariantOperand(@NotNull CBodyBuilder bodyBuilder,
                                                              @NotNull LirVariable operandVar,
                                                              @NotNull String role) {
        if (operandVar.type() instanceof GdVariantType) {
            return new VariantOperand(bodyBuilder.valueOfVar(operandVar), null);
        }

        var tempVariant = bodyBuilder.newTempVariable("op_" + role + "_variant", GdVariantType.VARIANT);
        bodyBuilder.declareTempVar(tempVariant);
        var packFunctionName = bodyBuilder.helper().renderPackFunctionName(operandVar.type());
        bodyBuilder.callAssign(
                tempVariant,
                packFunctionName,
                GdVariantType.VARIANT,
                List.of(bodyBuilder.valueOfVar(operandVar))
        );
        return new VariantOperand(tempVariant, tempVariant);
    }

    private void emitVariantEvaluateFailureReturn(@NotNull CBodyBuilder bodyBuilder,
                                                  @NotNull CBodyBuilder.TempVar resultVariant,
                                                  @NotNull VariantOperand leftOperand,
                                                  @NotNull VariantOperand rightOperand) {
        bodyBuilder.assignExpr(resultVariant, "godot_new_Variant_nil()", GdVariantType.VARIANT);
        bodyBuilder.destroyTempVar(resultVariant);
        if (rightOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(rightOperand.tempVar());
        }
        if (leftOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(leftOperand.tempVar());
        }

        var returnType = bodyBuilder.func().getReturnType();
        if (returnType instanceof GdVoidType) {
            bodyBuilder.returnVoid();
            return;
        }
        var defaultExpr = CBodyBuilder.renderDefaultValueExpr(returnType);
        bodyBuilder.returnValue(bodyBuilder.valueOfExpr(defaultExpr, returnType));
    }

    private void emitPrimitiveComparison(@NotNull CBodyBuilder bodyBuilder,
                                         @NotNull LirVariable resultVar,
                                         @NotNull GodotOperator op,
                                         @NotNull LirVariable leftVar,
                                         @NotNull LirVariable rightVar) {
        var leftCode = bodyBuilder.valueOfVar(leftVar).generateCode();
        var rightCode = bodyBuilder.valueOfVar(rightVar).generateCode();
        var expression = switch (op) {
            case EQUAL -> leftCode + " == " + rightCode;
            case NOT_EQUAL -> leftCode + " != " + rightCode;
            case LESS -> leftCode + " < " + rightCode;
            case LESS_EQUAL -> leftCode + " <= " + rightCode;
            case GREATER -> leftCode + " > " + rightCode;
            case GREATER_EQUAL -> leftCode + " >= " + rightCode;
            default -> throw bodyBuilder.invalidInsn(
                    "Primitive comparison path only supports compare operators, but got '" + op.name() + "'");
        };
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVar), "(" + expression + ")", GdBoolType.BOOL);
    }

    private void emitObjectComparison(@NotNull CBodyBuilder bodyBuilder,
                                      @NotNull LirVariable resultVar,
                                      @NotNull GodotOperator op,
                                      @NotNull LirVariable leftVar,
                                      @NotNull LirVariable rightVar) {
        if (op != GodotOperator.EQUAL && op != GodotOperator.NOT_EQUAL) {
            throw bodyBuilder.invalidInsn("Object comparison supports only == and !=");
        }

        var cmpArgs = List.of(bodyBuilder.valueOfVar(leftVar), bodyBuilder.valueOfVar(rightVar));
        if (op == GodotOperator.EQUAL) {
            bodyBuilder.callAssign(
                    bodyBuilder.targetOfVar(resultVar),
                    "gdcc_cmp_object",
                    GdBoolType.BOOL,
                    cmpArgs
            );
            return;
        }

        var tempCmpResult = bodyBuilder.newTempVariable("gdcc_cmp_object_eq", GdBoolType.BOOL);
        bodyBuilder.declareTempVar(tempCmpResult);
        bodyBuilder.callAssign(tempCmpResult, "gdcc_cmp_object", GdBoolType.BOOL, cmpArgs);
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVar),
                "!" + tempCmpResult.name(),
                GdBoolType.BOOL
        );
    }

    private void emitNilComparison(@NotNull CBodyBuilder bodyBuilder,
                                   @NotNull LirVariable resultVar,
                                   @NotNull GodotOperator op,
                                   @NotNull LirVariable leftVar,
                                   @NotNull LirVariable rightVar) {
        if (op != GodotOperator.EQUAL && op != GodotOperator.NOT_EQUAL) {
            throw bodyBuilder.invalidInsn("Nil comparison specialization supports only == and !=");
        }

        var leftIsNil = leftVar.type() instanceof GdNilType;
        var rightIsNil = rightVar.type() instanceof GdNilType;

        var equalsExpression = "false";
        if (leftIsNil && rightIsNil) {
            equalsExpression = "true";
        } else if (leftIsNil && rightVar.type() instanceof GdObjectType) {
            equalsExpression = bodyBuilder.valueOfVar(rightVar).generateCode() + " == NULL";
        } else if (rightIsNil && leftVar.type() instanceof GdObjectType) {
            equalsExpression = bodyBuilder.valueOfVar(leftVar).generateCode() + " == NULL";
        }

        var expression = op == GodotOperator.EQUAL
                ? equalsExpression
                : "!(" + equalsExpression + ")";
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVar), "(" + expression + ")", GdBoolType.BOOL);
    }

    private void throwUnimplementedPath(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull String insnName,
                                        @NotNull String opName,
                                        @NotNull String reason,
                                        @NotNull GdType leftType,
                                        GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(insnName);
        Objects.requireNonNull(opName);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(leftType);

        var rightTypeText = rightType == null ? "" : ", rightType='" + rightType.getTypeName() + "'";
        throw bodyBuilder.invalidInsn(
                "Operator path is not implemented for " + insnName + " operator '" + opName + "' " +
                        "(leftType='" + leftType.getTypeName() + "'" + rightTypeText + "): " + reason
        );
    }

    private void validateResultCompatibility(@NotNull CBodyBuilder bodyBuilder,
                                             @NotNull OperatorResolver.OperatorPath resolvedPath,
                                             @NotNull GdType semanticResultType,
                                             @NotNull LirVariable resultVar) {
        if (resolvedPath == OperatorResolver.OperatorPath.VARIANT_EVALUATE) {
            validateVariantEvaluateResultTarget(bodyBuilder, resultVar);
            return;
        }
        if (!bodyBuilder.classRegistry().checkAssignable(semanticResultType, resultVar.type())) {
            throw bodyBuilder.invalidInsn(
                    "Operator result type '" + semanticResultType.getTypeName() +
                            "' is not assignable to result variable '" + resultVar.id() +
                            "' of type '" + resultVar.type().getTypeName() + "'");
        }
    }

    private void validateVariantEvaluateResultTarget(@NotNull CBodyBuilder bodyBuilder,
                                                     @NotNull LirVariable resultVar) {
        if (resultVar.type() instanceof GdVoidType) {
            throw bodyBuilder.invalidInsn("variant_evaluate result variable cannot be void");
        }
        if (resultVar.type() instanceof GdVariantType) {
            return;
        }
        // Variant result is unpacked at runtime. Ensure target type is mappable to GDExtension enum first.
        resolver.resolveVariantTypeEnumLiteral(bodyBuilder, resultVar.type());
    }

    private @NotNull LirVariable resolveRequiredResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                                               String resultId) {
        if (resultId == null || resultId.isBlank()) {
            throw bodyBuilder.invalidInsn("Operator instruction missing required result variable ID");
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' cannot be a reference");
        }
        return resultVar;
    }

    private @NotNull LirVariable resolveOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                        @NotNull String variableId,
                                                        @NotNull String role) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("Operator " + role + " operand variable ID '" + variableId + "' not found in function");
        }
        return variable;
    }

    private record VariantOperand(@NotNull CBodyBuilder.ValueRef variantValue,
                                  @Nullable CBodyBuilder.TempVar tempVar) {
        private VariantOperand {
            Objects.requireNonNull(variantValue);
        }
    }

    private record PrimitiveFastPathGuardRule(@NotNull String invalidConditionExpr,
                                              @NotNull String reason) {
        private static final @NotNull PrimitiveFastPathGuardRule NOOP =
                new PrimitiveFastPathGuardRule("false", "no guard violation");

        private PrimitiveFastPathGuardRule {
            Objects.requireNonNull(invalidConditionExpr);
            Objects.requireNonNull(reason);
        }
    }
}
