package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(CallMethodInsnGen.class);

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
            warnStaticMethodCall(bodyBuilder, receiverVar, resolved);
        }

        var callArgs = validateFixedArgsAndCompleteDefaults(bodyBuilder, receiverVar, resolved, argVars);
        var fixedCount = resolved.parameters().size();
        var fixedArgs = callArgs.fixedArgs();

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
            destroyDefaultTemps(bodyBuilder, callArgs.defaultTemps());
            return;
        }

        var target = resolveResultTarget(bodyBuilder, instruction, resolved);
        bodyBuilder.callAssign(target, resolved.cFunctionName(), returnType, fixedArgs, varargs);
        destroyDefaultTemps(bodyBuilder, callArgs.defaultTemps());
    }

    private record CompletedCallArgs(@NotNull List<CBodyBuilder.ValueRef> fixedArgs,
                                     @NotNull List<CBodyBuilder.TempVar> defaultTemps) {
        private CompletedCallArgs {
            fixedArgs = List.copyOf(fixedArgs);
            defaultTemps = List.copyOf(defaultTemps);
        }
    }

    private void warnStaticMethodCall(@NotNull CBodyBuilder bodyBuilder,
                                      @NotNull LirVariable receiverVar,
                                      @NotNull MethodCallResolver.ResolvedMethodCall resolved) {
        var block = bodyBuilder.currentBlock();
        var blockId = block != null ? block.id() : "unknown";
        LOGGER.warn("call_method on receiver '{}' resolved static method '{}.{}'; emitting static call '{}' (function='{}', block='{}', insnIndex={})",
                receiverVar.id(),
                resolved.ownerClassName(),
                resolved.methodName(),
                resolved.cFunctionName(),
                bodyBuilder.func().getName(),
                blockId,
                bodyBuilder.currentInsnIndex());
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

    private @NotNull CompletedCallArgs validateFixedArgsAndCompleteDefaults(@NotNull CBodyBuilder bodyBuilder,
                                                                            @NotNull LirVariable receiverVar,
                                                                            @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                                                            @NotNull List<LirVariable> argVars) {
        var providedCount = argVars.size();
        var fixedCount = resolved.parameters().size();
        if (!resolved.isVararg() && providedCount > fixedCount) {
            throw bodyBuilder.invalidInsn("Too many arguments for method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "': expected " + fixedCount + ", got " + providedCount);
        }

        var fixedArgs = new ArrayList<CBodyBuilder.ValueRef>(fixedCount + (resolved.isStatic() ? 0 : 1));
        if (!resolved.isStatic()) {
            fixedArgs.add(renderReceiverValue(bodyBuilder, receiverVar, resolved.ownerType()));
        }
        var defaultTemps = new ArrayList<CBodyBuilder.TempVar>(Math.max(0, fixedCount - providedCount));

        var providedFixedCount = Math.min(providedCount, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argVar = argVars.get(i);
            var param = resolved.parameters().get(i);
            if (!bodyBuilder.classRegistry().checkAssignable(argVar.type(), param.type())) {
                throw bodyBuilder.invalidInsn("Cannot assign value of type '" + argVar.type().getTypeName() +
                        "' to method parameter #" + (i + 1) + " ('" + param.name() +
                        "') of type '" + param.type().getTypeName() + "'");
            }
            fixedArgs.add(bodyBuilder.valueOfVar(argVar));
        }

        for (var i = providedFixedCount; i < fixedCount; i++) {
            var param = resolved.parameters().get(i);
            if (!param.hasDefaultValue()) {
                throw bodyBuilder.invalidInsn("Too few arguments for method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "': missing required parameter #" + (i + 1) +
                        " ('" + param.name() + "')");
            }

            var temp = bodyBuilder.newTempVariable("default_arg_" + (i + 1), param.type());
            bodyBuilder.declareTempVar(temp);
            switch (param.defaultKind()) {
                case LITERAL -> materializeLiteralDefault(bodyBuilder, resolved, param, temp, i + 1);
                case FUNCTION -> materializeFunctionDefault(bodyBuilder, receiverVar, resolved, param, temp, i + 1);
                case NONE -> throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "' parameter #" + (i + 1) +
                        " has no default value metadata");
            }
            fixedArgs.add(temp);
            defaultTemps.add(temp);
        }

        return new CompletedCallArgs(fixedArgs, defaultTemps);
    }

    private void materializeLiteralDefault(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                           @NotNull MethodCallResolver.MethodParamSpec param,
                                           @NotNull CBodyBuilder.TempVar temp,
                                           int parameterIndexBaseOne) {
        var literal = param.defaultLiteral();
        if (literal == null || literal.isBlank()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " has empty literal default metadata");
        }
        bodyBuilder.helper().builtinBuilder().materializeUtilityDefaultValue(
                bodyBuilder,
                temp,
                literal,
                resolved.ownerClassName() + "." + resolved.methodName(),
                parameterIndexBaseOne
        );
    }

    private void materializeFunctionDefault(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull LirVariable receiverVar,
                                            @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                            @NotNull MethodCallResolver.MethodParamSpec param,
                                            @NotNull CBodyBuilder.TempVar temp,
                                            int parameterIndexBaseOne) {
        var defaultFunctionName = param.defaultFunctionName();
        if (defaultFunctionName == null || defaultFunctionName.isBlank()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " has empty default_value_func metadata");
        }

        var ownerClassDef = bodyBuilder.classRegistry().getClassDef(new GdObjectType(resolved.ownerClassName()));
        if (ownerClassDef == null) {
            throw bodyBuilder.invalidInsn("Method owner type '" + resolved.ownerClassName() +
                    "' metadata is missing while resolving default_value_func '" + defaultFunctionName + "'");
        }

        var defaultFunction = findDefaultFunction(bodyBuilder, ownerClassDef.getFunctions(), resolved, defaultFunctionName);
        validateDefaultFunctionContract(bodyBuilder, resolved, receiverVar, param, defaultFunction, parameterIndexBaseOne);

        var defaultCallArgs = new ArrayList<CBodyBuilder.ValueRef>(1);
        if (!defaultFunction.isStatic()) {
            defaultCallArgs.add(renderReceiverValue(bodyBuilder, receiverVar, new GdObjectType(resolved.ownerClassName())));
        }
        var defaultCFunctionName = resolved.ownerClassName() + "_" + defaultFunctionName;
        bodyBuilder.callAssign(temp, defaultCFunctionName, param.type(), defaultCallArgs);
    }

    private @NotNull FunctionDef findDefaultFunction(@NotNull CBodyBuilder bodyBuilder,
                                                     @NotNull List<? extends FunctionDef> ownerFunctions,
                                                     @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                                     @NotNull String defaultFunctionName) {
        FunctionDef found = null;
        for (var function : ownerFunctions) {
            if (!defaultFunctionName.equals(function.getName())) {
                continue;
            }
            if (found != null) {
                throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                        "' default_value_func '" + defaultFunctionName + "' is ambiguous in owner '" +
                        resolved.ownerClassName() + "'");
            }
            found = function;
        }
        if (found == null) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' default_value_func '" + defaultFunctionName + "' is missing in owner '" +
                    resolved.ownerClassName() + "'");
        }
        return found;
    }

    private void validateDefaultFunctionContract(@NotNull CBodyBuilder bodyBuilder,
                                                 @NotNull MethodCallResolver.ResolvedMethodCall resolved,
                                                 @NotNull LirVariable receiverVar,
                                                 @NotNull MethodCallResolver.MethodParamSpec param,
                                                 @NotNull FunctionDef defaultFunction,
                                                 int parameterIndexBaseOne) {
        if (defaultFunction.isVararg()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                    defaultFunction.getName() + "' must not be vararg");
        }
        var expectedParamCount = defaultFunction.isStatic() ? 0 : 1;
        if (defaultFunction.getParameterCount() != expectedParamCount) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                    defaultFunction.getName() + "' expects " + defaultFunction.getParameterCount() +
                    " parameters, but only " + expectedParamCount + " is supported");
        }
        if (!defaultFunction.isStatic()) {
            var selfParam = defaultFunction.getParameter(0);
            if (selfParam == null || !bodyBuilder.classRegistry().checkAssignable(receiverVar.type(), selfParam.getType())) {
                throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                        "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                        defaultFunction.getName() + "' has incompatible receiver parameter type");
            }
        }
        if (!bodyBuilder.classRegistry().checkAssignable(defaultFunction.getReturnType(), param.type())) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                    defaultFunction.getName() + "' returns '" + defaultFunction.getReturnType().getTypeName() +
                    "', incompatible with parameter type '" + param.type().getTypeName() + "'");
        }
    }

    private void destroyDefaultTemps(@NotNull CBodyBuilder bodyBuilder,
                                     @NotNull List<CBodyBuilder.TempVar> defaultTemps) {
        for (var i = defaultTemps.size() - 1; i >= 0; i--) {
            bodyBuilder.destroyTempVar(defaultTemps.get(i));
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
