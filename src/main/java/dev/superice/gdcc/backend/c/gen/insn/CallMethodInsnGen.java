package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.LineNumberInsn;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// C code generator for `CALL_METHOD`.
///
/// Current scope:
/// - GDCC / ENGINE / BUILTIN static dispatch.
/// - OBJECT_DYNAMIC / VARIANT_DYNAMIC runtime dispatch.
/// - Argument/result contract validation with default argument completion.
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

        var resolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, instruction.methodName(), argVars);
        switch (resolved.mode()) {
            case OBJECT_DYNAMIC -> emitObjectDynamicCall(bodyBuilder, instruction, receiverVar, argVars);
            case VARIANT_DYNAMIC -> emitVariantDynamicCall(bodyBuilder, instruction, receiverVar, argVars);
            case GDCC, ENGINE, BUILTIN ->
                    emitKnownSignatureCall(bodyBuilder, instruction, receiverVar, argVars, resolved);
            default -> throw bodyBuilder.invalidInsn("Unsupported call_method dispatch mode: " + resolved.mode());
        }
    }

    private record DynamicVariantArgs(@NotNull List<CBodyBuilder.ValueRef> values,
                                      @NotNull List<CBodyBuilder.TempVar> packedTemps) {
        private DynamicVariantArgs {
            values = List.copyOf(values);
            packedTemps = List.copyOf(packedTemps);
        }
    }

    private record VariantDynamicSourceLocation(@NotNull String fileName, int lineNumber) {
        private VariantDynamicSourceLocation {
            fileName = fileName.isBlank() ? "<unknown>" : fileName;
        }
    }

    private void emitObjectDynamicCall(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull CallMethodInsn instruction,
                                       @NotNull LirVariable receiverVar,
                                       @NotNull List<LirVariable> argVars) {
        var dynamicArgs = materializeDynamicVariantArgs(bodyBuilder, argVars, "object_dynamic");
        var fixedArgs = List.of(
                bodyBuilder.valueOfVar(receiverVar),
                bodyBuilder.valueOfStringNamePtrLiteral(instruction.methodName())
        );
        emitDynamicCallResult(
                bodyBuilder,
                instruction,
                "godot_Object_call",
                fixedArgs,
                dynamicArgs.values(),
                dynamicArgs.packedTemps()
        );
    }

    private void emitVariantDynamicCall(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull CallMethodInsn instruction,
                                        @NotNull LirVariable receiverVar,
                                        @NotNull List<LirVariable> argVars) {
        var dynamicArgs = materializeDynamicVariantArgs(bodyBuilder, argVars, "variant_dynamic");
        var sourceLocation = resolveVariantDynamicSourceLocation(bodyBuilder);
        var fixedArgs = List.of(
                bodyBuilder.valueOfVar(receiverVar),
                bodyBuilder.valueOfStringNamePtrLiteral(instruction.methodName()),
                bodyBuilder.valueOfCStringLiteral(sourceLocation.fileName()),
                bodyBuilder.valueOfExpr(Integer.toString(sourceLocation.lineNumber()), GdIntType.INT)
        );
        emitDynamicCallResult(
                bodyBuilder,
                instruction,
                "godot_Variant_call",
                fixedArgs,
                dynamicArgs.values(),
                dynamicArgs.packedTemps()
        );
    }

    private @NotNull VariantDynamicSourceLocation resolveVariantDynamicSourceLocation(@NotNull CBodyBuilder bodyBuilder) {
        var classSourceFile = bodyBuilder.clazz().getSourceFile();
        var baseFileName = classSourceFile != null && !classSourceFile.isBlank()
                ? classSourceFile
                : bodyBuilder.clazz().getName();
        var lineNumber = findNearestLineNumberBeforeCurrentInsn(bodyBuilder);
        if (lineNumber > 0) {
            return new VariantDynamicSourceLocation(baseFileName, lineNumber);
        }
        var fallbackLine = Math.max(0, bodyBuilder.currentInsnIndex());
        return new VariantDynamicSourceLocation(baseFileName + "(assemble)", fallbackLine);
    }

    private int findNearestLineNumberBeforeCurrentInsn(@NotNull CBodyBuilder bodyBuilder) {
        var block = bodyBuilder.currentBlock();
        if (block == null) {
            return -1;
        }
        var instructions = block.instructions();
        var upperBoundExclusive = Math.min(bodyBuilder.currentInsnIndex(), instructions.size());
        var nearestLine = -1;
        for (var i = 0; i < upperBoundExclusive; i++) {
            var insn = instructions.get(i);
            if (insn instanceof LineNumberInsn(int lineNumber) && lineNumber > 0) {
                nearestLine = lineNumber;
            }
        }
        return nearestLine;
    }

    private void emitDynamicCallResult(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull CallMethodInsn instruction,
                                       @NotNull String cFunctionName,
                                       @NotNull List<CBodyBuilder.ValueRef> fixedArgs,
                                       @Nullable List<CBodyBuilder.ValueRef> varargs,
                                       @NotNull List<CBodyBuilder.TempVar> dynamicArgTemps) {
        var resultVar = resolveDynamicResultVariable(bodyBuilder, instruction);
        if (resultVar == null) {
            bodyBuilder.callAssign(bodyBuilder.discardRef(), cFunctionName, GdVariantType.VARIANT, fixedArgs, varargs);
            destroyDefaultTemps(bodyBuilder, dynamicArgTemps);
            return;
        }

        if (resultVar.type() instanceof GdVariantType) {
            bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), cFunctionName, GdVariantType.VARIANT, fixedArgs, varargs);
            destroyDefaultTemps(bodyBuilder, dynamicArgTemps);
            return;
        }

        var dynamicResultTemp = bodyBuilder.newTempVariable("dynamic_result", GdVariantType.VARIANT);
        bodyBuilder.declareTempVar(dynamicResultTemp);
        bodyBuilder.callAssign(dynamicResultTemp, cFunctionName, GdVariantType.VARIANT, fixedArgs, varargs);
        var unpackFunctionName = bodyBuilder.helper().renderUnpackFunctionName(resultVar.type());
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                unpackFunctionName,
                resultVar.type(),
                List.of(dynamicResultTemp)
        );
        bodyBuilder.destroyTempVar(dynamicResultTemp);
        destroyDefaultTemps(bodyBuilder, dynamicArgTemps);
    }

    private @NotNull DynamicVariantArgs materializeDynamicVariantArgs(@NotNull CBodyBuilder bodyBuilder,
                                                                      @NotNull List<LirVariable> argVars,
                                                                      @NotNull String tempPrefix) {
        var values = new ArrayList<CBodyBuilder.ValueRef>(argVars.size());
        var temps = new ArrayList<CBodyBuilder.TempVar>();
        for (var i = 0; i < argVars.size(); i++) {
            var argVar = argVars.get(i);
            if (argVar.type() instanceof GdVariantType) {
                values.add(bodyBuilder.valueOfVar(argVar));
                continue;
            }

            var packedVariantTemp = bodyBuilder.newTempVariable(tempPrefix + "_arg_" + (i + 1), GdVariantType.VARIANT);
            bodyBuilder.declareTempVar(packedVariantTemp);
            var packFunctionName = bodyBuilder.helper().renderPackFunctionName(argVar.type());
            bodyBuilder.callAssign(
                    packedVariantTemp,
                    packFunctionName,
                    GdVariantType.VARIANT,
                    List.of(bodyBuilder.valueOfVar(argVar))
            );
            values.add(packedVariantTemp);
            temps.add(packedVariantTemp);
        }
        return new DynamicVariantArgs(values, temps);
    }

    private void emitKnownSignatureCall(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull CallMethodInsn instruction,
                                        @NotNull LirVariable receiverVar,
                                        @NotNull List<LirVariable> argVars,
                                        @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
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
                                      @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
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

    private @NotNull CompletedCallArgs validateFixedArgsAndCompleteDefaults(@NotNull CBodyBuilder bodyBuilder,
                                                                            @NotNull LirVariable receiverVar,
                                                                            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                                                            @NotNull List<LirVariable> argVars) {
        var providedCount = argVars.size();
        var fixedCount = resolved.parameters().size();
        if (!resolved.isVararg() && providedCount > fixedCount) {
            throw bodyBuilder.invalidInsn("Too many arguments for method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "': expected " + fixedCount + ", got " + providedCount);
        }

        var fixedArgs = new ArrayList<CBodyBuilder.ValueRef>(fixedCount + (resolved.isStatic() ? 0 : 1));
        if (!resolved.isStatic()) {
            fixedArgs.add(BackendPropertyAccessResolver.renderReceiverValue(
                    bodyBuilder,
                    receiverVar,
                    resolved.ownerType(),
                    "CALL_METHOD",
                    "method owner",
                    ""
            ));
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
                                           @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                           @NotNull BackendMethodCallResolver.MethodParamSpec param,
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
                                            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                            @NotNull BackendMethodCallResolver.MethodParamSpec param,
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
            defaultCallArgs.add(BackendPropertyAccessResolver.renderReceiverValue(
                    bodyBuilder,
                    receiverVar,
                    new GdObjectType(resolved.ownerClassName()),
                    "CALL_METHOD",
                    "method owner",
                    ""
            ));
        }
        var defaultCFunctionName = resolved.ownerClassName() + "_" + defaultFunctionName;
        bodyBuilder.callAssign(temp, defaultCFunctionName, param.type(), defaultCallArgs);
    }

    private @NotNull FunctionDef findDefaultFunction(@NotNull CBodyBuilder bodyBuilder,
                                                     @NotNull List<? extends FunctionDef> ownerFunctions,
                                                     @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
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
                                                 @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                                 @NotNull LirVariable receiverVar,
                                                 @NotNull BackendMethodCallResolver.MethodParamSpec param,
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
                                 @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
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
                                                                @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
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

    private @Nullable LirVariable resolveDynamicResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                                               @NotNull CallMethodInsn instruction) {
        var resultId = instruction.resultId();
        if (resultId == null) {
            return null;
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
}
