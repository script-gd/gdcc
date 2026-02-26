package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// C code generator for `CALL_METHOD`.
///
/// Phase 1 scope:
/// - GDCC / ENGINE / BUILTIN static dispatch.
/// - Argument/result contract validation.
/// - Known object type + unknown method fails fast.
/// - Dynamic paths (OBJECT_DYNAMIC / VARIANT_DYNAMIC) are intentionally deferred.
public final class CallMethodInsnGen implements CInsnGen<CallMethodInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_METHOD);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var receiverVar = resolveReceiverVar(bodyBuilder, instruction.objectId());
        var argVars = resolveArgumentVariables(bodyBuilder, instruction, instruction.args());

        var resolved = MethodCallResolver.resolve(bodyBuilder, receiverVar, instruction.methodName(), argVars);
        switch (resolved.mode()) {
            case OBJECT_DYNAMIC -> throw bodyBuilder.invalidInsn("call_method dynamic object dispatch is not implemented in Phase 1: receiver type '" +
                    receiverVar.type().getTypeName() + "'");
            case VARIANT_DYNAMIC -> throw bodyBuilder.invalidInsn("call_method dynamic Variant dispatch is not implemented in Phase 1");
            case GDCC, ENGINE, BUILTIN -> emitKnownSignatureCall(bodyBuilder, instruction, receiverVar, argVars, resolved);
            default -> throw bodyBuilder.invalidInsn("Unsupported call_method dispatch mode: " + resolved.mode());
        }
    }

    private void emitKnownSignatureCall(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull CallMethodInsn instruction,
                                        @NotNull LirVariable receiverVar,
                                        @NotNull List<LirVariable> argVars,
                                        @NotNull MethodCallResolver.ResolvedMethodCall resolved) {
        if (resolved.isStatic()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' is static and cannot be called by call_method");
        }

        validateFixedArguments(bodyBuilder, resolved, argVars);
        var fixedCount = resolved.parameters().size();
        var fixedArgs = new ArrayList<CBodyBuilder.ValueRef>(fixedCount + 1);
        fixedArgs.add(renderReceiverValue(bodyBuilder, receiverVar, resolved.ownerType()));
        for (var i = 0; i < fixedCount; i++) {
            fixedArgs.add(bodyBuilder.valueOfVar(argVars.get(i)));
        }

        List<CBodyBuilder.ValueRef> varargs = null;
        if (resolved.isVararg()) {
            varargs = new ArrayList<>(Math.max(0, argVars.size() - fixedCount));
            for (var i = fixedCount; i < argVars.size(); i++) {
                varargs.add(bodyBuilder.valueOfVar(argVars.get(i)));
            }
            validateVarargs(bodyBuilder, resolved, argVars, fixedCount);
        }

        var returnType = resolved.returnType();
        if (returnType instanceof GdVoidType) {
            if (instruction.resultId() != null) {
                throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                        "' has no return value but resultId is provided");
            }
            bodyBuilder.callVoid(resolved.cFunctionName(), fixedArgs, varargs);
            return;
        }

        var target = resolveResultTarget(bodyBuilder, instruction, resolved);
        bodyBuilder.callAssign(target, resolved.cFunctionName(), returnType, fixedArgs, varargs);
    }

    private @NotNull CBodyBuilder.ValueRef renderReceiverValue(@NotNull CBodyBuilder bodyBuilder,
                                                               @NotNull LirVariable receiverVar,
                                                               @NotNull GdType ownerType) {
        if (ownerType instanceof GdObjectType ownerObjectType &&
                receiverVar.type() instanceof GdObjectType receiverObjectType &&
                !ownerObjectType.getTypeName().equals(receiverObjectType.getTypeName())) {
            if (!bodyBuilder.classRegistry().checkAssignable(receiverObjectType, ownerObjectType)) {
                throw bodyBuilder.invalidInsn("Receiver type '" + receiverObjectType.getTypeName() +
                        "' is not assignable to method owner type '" + ownerObjectType.getTypeName() + "'");
            }
            var castType = bodyBuilder.helper().renderGdTypeInC(ownerObjectType);
            var castExpr = "(" + castType + ")$" + receiverVar.id();
            return bodyBuilder.valueOfExpr(castExpr, ownerObjectType);
        }
        return bodyBuilder.valueOfVar(receiverVar);
    }

    private void validateFixedArguments(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                        @NotNull List<LirVariable> argVars) {
        var providedCount = argVars.size();
        var fixedCount = resolved.parameters().size();
        if (providedCount < fixedCount) {
            throw bodyBuilder.invalidInsn("Too few arguments for method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "': expected at least " + fixedCount + ", got " + providedCount);
        }
        if (!resolved.isVararg() && providedCount > fixedCount) {
            throw bodyBuilder.invalidInsn("Too many arguments for method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "': expected " + fixedCount + ", got " + providedCount);
        }
        for (var i = 0; i < fixedCount; i++) {
            var argVar = argVars.get(i);
            var param = resolved.parameters().get(i);
            if (!bodyBuilder.classRegistry().checkAssignable(argVar.type(), param.type())) {
                throw bodyBuilder.invalidInsn("Cannot assign value of type '" + argVar.type().getTypeName() +
                        "' to method parameter #" + (i + 1) + " ('" + param.name() +
                        "') of type '" + param.type().getTypeName() + "'");
            }
        }
    }

    private void validateVarargs(@NotNull CBodyBuilder bodyBuilder,
                                 @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                 @NotNull List<LirVariable> argVars,
                                 int fixedCount) {
        for (var i = fixedCount; i < argVars.size(); i++) {
            var argType = argVars.get(i).type();
            if (!bodyBuilder.classRegistry().checkAssignable(argType, GdVariantType.VARIANT)) {
                throw bodyBuilder.invalidInsn("Vararg argument #" + (i + 1) + " of method '" +
                        resolved.ownerClassName() + "." + resolved.methodName() +
                        "' must be Variant, got '" + argType.getTypeName() + "'");
            }
        }
    }

    private @NotNull LirVariable resolveReceiverVar(@NotNull CBodyBuilder bodyBuilder,
                                                    @NotNull String receiverId) {
        var receiverVar = bodyBuilder.func().getVariableById(receiverId);
        if (receiverVar == null) {
            throw bodyBuilder.invalidInsn("Receiver variable ID '" + receiverId + "' not found in function");
        }
        if (receiverVar.type() instanceof GdVoidType) {
            throw bodyBuilder.invalidInsn("Receiver variable '" + receiverId + "' cannot be void");
        }
        return receiverVar;
    }

    private @NotNull List<LirVariable> resolveArgumentVariables(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull CallMethodInsn instruction,
                                                                @NotNull List<LirInstruction.Operand> operands) {
        var out = new ArrayList<LirVariable>(operands.size());
        for (var i = 0; i < operands.size(); i++) {
            var operand = operands.get(i);
            if (!(operand instanceof LirInstruction.VariableOperand(var argId))) {
                throw bodyBuilder.invalidInsn("Argument #" + (i + 1) + " of method '" + instruction.methodName() +
                        "' must be a variable operand");
            }
            var argVar = bodyBuilder.func().getVariableById(argId);
            if (argVar == null) {
                throw bodyBuilder.invalidInsn("Argument variable ID '" + argId + "' not found in function");
            }
            out.add(argVar);
        }
        return out;
    }

    private @NotNull CBodyBuilder.TargetRef resolveResultTarget(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull CallMethodInsn instruction,
                                                                @NotNull MethodCallResolver.ResolvedMethodCall resolved) {
        var resultId = instruction.resultId();
        if (resultId == null) {
            return bodyBuilder.discardRef();
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' cannot be a reference");
        }
        if (!bodyBuilder.classRegistry().checkAssignable(resolved.returnType(), resultVar.type())) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' returns '" + resolved.returnType().getTypeName() + "', but result variable '" + resultId +
                    "' has incompatible type '" + resultVar.type().getTypeName() + "'");
        }
        return bodyBuilder.targetOfVar(resultVar);
    }
}
