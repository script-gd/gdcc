package gd.script.gdcc.lir.validation;

import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.LirSignalDef;
import gd.script.gdcc.util.TypeCheckUtil;
import org.jetbrains.annotations.NotNull;

/// Validates that compiler-only types never leak into public ABI-like LIR surfaces.
///
/// The current MVP contract only allows compiler-only types on function-local variables.
/// Hidden functions are validated the same way as public ones so backend/template code never
/// has to guess whether a hidden signature is safe to expose through later wiring.
public final class LirPublicAbiValidator {

    public void validateModule(@NotNull LirModule module) {
        for (var classDef : module.getClassDefs()) {
            validateClass(classDef);
        }
    }

    public void validateClass(@NotNull LirClassDef classDef) {
        for (var propertyDef : classDef.getProperties()) {
            validateProperty(classDef, propertyDef);
        }
        for (var signalDef : classDef.getSignals()) {
            validateSignal(classDef, signalDef);
        }
        for (var functionDef : classDef.getFunctions()) {
            validateFunction(classDef, functionDef);
        }
    }

    public void validateFunction(@NotNull LirClassDef classDef, @NotNull LirFunctionDef functionDef) {
        for (var parameterDef : functionDef.getParameters()) {
            TypeCheckUtil.requireNonCompilerOnly(
                    parameterDef.getType(),
                    "function parameter at " + describeFunction(classDef, functionDef) + "(" + parameterDef.getName() + ")"
            );
        }
        for (var captureDef : functionDef.getCaptures().values()) {
            TypeCheckUtil.requireNonCompilerOnly(
                    captureDef.getType(),
                    "function capture at " + describeFunction(classDef, functionDef) + "(" + captureDef.getName() + ")"
            );
        }
        TypeCheckUtil.requireNonCompilerOnly(
                functionDef.getReturnType(),
                "function return at " + describeFunction(classDef, functionDef)
        );
    }

    private void validateProperty(@NotNull LirClassDef classDef, @NotNull LirPropertyDef propertyDef) {
        TypeCheckUtil.requireNonCompilerOnly(
                propertyDef.getType(),
                "property at " + classDef.getName() + "." + propertyDef.getName()
        );
    }

    private void validateSignal(@NotNull LirClassDef classDef, @NotNull LirSignalDef signalDef) {
        for (var parameterDef : signalDef.getParameters()) {
            TypeCheckUtil.requireNonCompilerOnly(
                    parameterDef.getType(),
                    "signal parameter at " + classDef.getName() + "." + signalDef.getName() + "(" + parameterDef.getName() + ")"
            );
        }
    }

    private @NotNull String describeFunction(@NotNull LirClassDef classDef, @NotNull LirFunctionDef functionDef) {
        return classDef.getName() + "." + functionDef.getName();
    }
}
