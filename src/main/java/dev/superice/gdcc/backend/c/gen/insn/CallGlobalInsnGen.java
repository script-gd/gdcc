package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// C code generator for `CALL_GLOBAL`.
///
/// Current scope only supports utility functions in class registry.
/// Validation is performed in generator and C emission is delegated to CBodyBuilder.
public final class CallGlobalInsnGen implements CInsnGen<CallGlobalInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_GLOBAL);
    }

    /// Generates C code for one `call_global` instruction.
    ///
    /// Flow: resolve utility -> validate/signature check -> route to CBodyBuilder call APIs.
    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var utility = requireUtilityCall(bodyBuilder, instruction.functionName());
        var argVars = resolveArgumentVariables(bodyBuilder, utility, instruction.args());
        validateArgumentCount(bodyBuilder, utility, argVars.size());

        var signature = utility.signature();
        var fixedCount = signature.parameterCount();
        var fixedArgs = new ArrayList<CBodyBuilder.ValueRef>(fixedCount);
        var defaultTemps = new ArrayList<CBodyBuilder.TempVar>(Math.max(0, fixedCount - argVars.size()));
        var varargs = new ArrayList<CBodyBuilder.ValueRef>(Math.max(0, argVars.size() - fixedCount));
        var providedFixedCount = Math.min(argVars.size(), fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var parameter = signature.parameters().get(i);
            var paramType = parameter.type();
            if (paramType == null) {
                throw bodyBuilder.invalidInsn("Utility parameter #" + (i + 1) + " of '" + utility.lookupName() +
                        "' has unresolved type metadata");
            }
            var argVar = argVars.get(i);
            if (!bodyBuilder.classRegistry().checkAssignable(argVar.type(), paramType)) {
                throw bodyBuilder.invalidInsn("Cannot assign value of type '" + argVar.type().getTypeName() +
                        "' to utility parameter #" + (i + 1) + " of type '" + paramType.getTypeName() + "'");
            }
            fixedArgs.add(bodyBuilder.valueOfVar(argVar));
        }

        for (var i = providedFixedCount; i < fixedCount; i++) {
            var parameter = signature.parameters().get(i);
            var paramType = parameter.type();
            if (paramType == null) {
                throw bodyBuilder.invalidInsn("Utility parameter #" + (i + 1) + " of '" + utility.lookupName() +
                        "' has unresolved type metadata");
            }
            if (parameter.defaultValue() == null) {
                throw bodyBuilder.invalidInsn("Too few arguments for utility function '" + utility.lookupName() +
                        "': missing required parameter #" + (i + 1));
            }
            var temp = bodyBuilder.newTempVariable("default_arg_" + (i + 1), paramType);
            bodyBuilder.declareTempVar(temp);
            bodyBuilder.helper().builtinBuilder().materializeUtilityDefaultValue(
                    bodyBuilder,
                    temp,
                    parameter.defaultValue(),
                    utility.lookupName(),
                    i + 1
            );
            fixedArgs.add(temp);
            defaultTemps.add(temp);
        }

        for (var i = fixedCount; i < argVars.size(); i++) {
            varargs.add(bodyBuilder.valueOfVar(argVars.get(i)));
        }
        if (signature.isVararg()) {
            validateVarargTypes(bodyBuilder, utility, varargs);
        }

        var returnType = signature.returnType();
        var callVarargs = signature.isVararg() ? varargs : null;
        if (returnType == null || returnType instanceof GdVoidType) {
            if (instruction.resultId() != null) {
                throw bodyBuilder.invalidInsn("Utility function '" + utility.lookupName() +
                        "' has no return value but resultId is provided");
            }
            bodyBuilder.callVoid(utility.cFunctionName(), fixedArgs, callVarargs);
        } else {
            var target = resolveResultTarget(bodyBuilder, instruction, utility.lookupName(), returnType);
            bodyBuilder.callAssign(target, utility.cFunctionName(), returnType, fixedArgs, callVarargs);
        }
        for (var i = defaultTemps.size() - 1; i >= 0; i--) {
            bodyBuilder.destroyTempVar(defaultTemps.get(i));
        }
    }

    private @NotNull CBodyBuilder.TargetRef resolveResultTarget(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull CallGlobalInsn instruction,
                                                                @NotNull String utilityLookupName,
                                                                @NotNull GdType returnType) {
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
        if (!bodyBuilder.classRegistry().checkAssignable(returnType, resultVar.type())) {
            throw bodyBuilder.invalidInsn("Utility function '" + utilityLookupName + "' returns '" +
                    returnType.getTypeName() + "', but result variable '" + resultId +
                    "' has incompatible type '" + resultVar.type().getTypeName() + "'");
        }
        return bodyBuilder.targetOfVar(resultVar);
    }

    private @NotNull CGenHelper.UtilityCallResolution requireUtilityCall(@NotNull CBodyBuilder bodyBuilder,
                                                                         @NotNull String functionName) {
        var utility = bodyBuilder.helper().resolveUtilityCall(functionName);
        if (utility == null) {
            var lookupName = bodyBuilder.helper().normalizeUtilityLookupName(functionName);
            throw bodyBuilder.invalidInsn("Global utility function '" + functionName +
                    "' not found in registry (lookup key: '" + lookupName + "')");
        }
        return utility;
    }

    private @NotNull List<LirVariable> resolveArgumentVariables(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull CGenHelper.UtilityCallResolution utility,
                                                                @NotNull List<LirInstruction.Operand> operands) {
        var argVars = new ArrayList<LirVariable>(operands.size());
        var func = bodyBuilder.func();
        for (var i = 0; i < operands.size(); i++) {
            var operand = operands.get(i);
            if (!(operand instanceof LirInstruction.VariableOperand(var argId))) {
                throw bodyBuilder.invalidInsn("Argument #" + (i + 1) + " of utility function '" +
                        utility.lookupName() + "' must be a variable operand");
            }
            var argVar = func.getVariableById(argId);
            if (argVar == null) {
                throw bodyBuilder.invalidInsn("Argument variable ID '" + argId + "' not found in function");
            }
            argVars.add(argVar);
        }
        return argVars;
    }

    private void validateArgumentCount(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull CGenHelper.UtilityCallResolution utility,
                                       int providedCount) {
        var signature = utility.signature();
        var fixedCount = signature.parameterCount();
        if (!signature.isVararg()) {
            if (providedCount > fixedCount) {
                throw bodyBuilder.invalidInsn("Too many arguments for utility function '" + utility.lookupName() +
                        "': expected " + fixedCount + ", got " + providedCount);
            }
        }
    }

    private void validateVarargTypes(@NotNull CBodyBuilder bodyBuilder,
                                     @NotNull CGenHelper.UtilityCallResolution utility,
                                     @NotNull List<CBodyBuilder.ValueRef> varargs) {
        for (var i = 0; i < varargs.size(); i++) {
            var arg = varargs.get(i);
            if (!bodyBuilder.classRegistry().checkAssignable(arg.type(), GdVariantType.VARIANT)) {
                var argIndex = utility.signature().parameterCount() + i + 1;
                throw bodyBuilder.invalidInsn("Vararg argument #" + argIndex + " of utility '" +
                        utility.lookupName() + "' must be Variant, got '" + arg.type().getTypeName() + "'");
            }
        }
    }
}
