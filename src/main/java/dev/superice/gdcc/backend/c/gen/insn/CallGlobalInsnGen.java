package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// C code generator for `CALL_GLOBAL`.
///
/// Current scope only supports utility functions in class registry.
/// Calls are delegated to CBodyBuilder utility APIs to keep vararg ABI and
/// lifecycle behavior centralized.
public final class CallGlobalInsnGen implements CInsnGen<CallGlobalInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_GLOBAL);
    }

    /// Generates C code for one `call_global` instruction.
    ///
    /// Flow: resolve utility -> resolve argument vars -> route to utility call API.
    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var utility = requireUtilityCall(bodyBuilder, instruction.functionName());
        var argVars = resolveArgumentVariables(bodyBuilder, utility, instruction.args());

        var args = new ArrayList<CBodyBuilder.ValueRef>(argVars.size());
        for (var argVar : argVars) {
            args.add(bodyBuilder.valueOfVar(argVar));
        }

        var returnType = utility.signature().returnType();
        if (returnType == null || returnType instanceof GdVoidType) {
            if (instruction.resultId() != null) {
                throw bodyBuilder.invalidInsn("Utility function '" + utility.lookupName() +
                        "' has no return value but resultId is provided");
            }
            // TODO: implement using callVoid
//            bodyBuilder.callUtilityVoid(utility, args);
            return;
        }

        var target = resolveResultTarget(bodyBuilder, instruction, utility.lookupName(), returnType);
        // TODO: implement using callAssign
//        bodyBuilder.callUtilityAssign(target, utility, args);
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
}
