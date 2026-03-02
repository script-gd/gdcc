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
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

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
        validateResultCompatibility(bodyBuilder, decision.semanticResultType(), resultVar);

        switch (decision.path()) {
            case BUILTIN_EVALUATOR -> emitUnaryBuiltinEvaluator(
                    bodyBuilder,
                    resultVar,
                    instruction.op(),
                    operandVar,
                    decision.semanticResultType()
            );
            default -> throw bodyBuilder.invalidInsn(
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
        validateResultCompatibility(bodyBuilder, decision.semanticResultType(), resultVar);

        switch (decision.path()) {
            case PRIMITIVE_COMPARISON ->
                    emitPrimitiveComparison(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
            case OBJECT_COMPARISON -> emitObjectComparison(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
            case NIL_COMPARISON -> emitNilComparison(bodyBuilder, resultVar, instruction.op(), leftVar, rightVar);
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
                op, leftVar.type(), rightVar.type(), semanticResultType
        );
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                helperFunctionName,
                semanticResultType,
                List.of(bodyBuilder.valueOfVar(leftVar), bodyBuilder.valueOfVar(rightVar))
        );
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
                                             @NotNull GdType semanticResultType,
                                             @NotNull LirVariable resultVar) {
        if (!bodyBuilder.classRegistry().checkAssignable(semanticResultType, resultVar.type())) {
            throw bodyBuilder.invalidInsn(
                    "Operator result type '" + semanticResultType.getTypeName() +
                            "' is not assignable to result variable '" + resultVar.id() +
                            "' of type '" + resultVar.type().getTypeName() + "'");
        }
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
}
