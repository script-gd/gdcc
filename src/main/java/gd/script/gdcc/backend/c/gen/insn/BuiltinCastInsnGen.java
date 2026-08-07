package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.BuiltinCastInsn;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.type.ExplicitCastDecision;
import gd.script.gdcc.util.type.ExplicitCastSupport;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/// Backend codegen for LIR `builtin_cast` (GDScript `as` to a non-Object runtime builtin).
///
/// Contract (see `frontend_cast_expression_implementation.md`):
/// - target must resolve to a non-Object / non-Variant / non-Nil runtime builtin
/// - parameterized `Array[T]` / `Dictionary[K, V]` use base ARRAY/DICTIONARY construct only
/// - non-Variant source packs once; construct via `godot_variant_construct` + `GDExtensionCallError`
/// - identity / Variant-target / Object decisions fail-fast if they reach this generator
public final class BuiltinCastInsnGen implements CInsnGen<BuiltinCastInsn> {
    private final OperatorResolver resolver = new OperatorResolver();

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.BUILTIN_CAST);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var targetTypeName = insn.targetTypeName().trim();
        if (targetTypeName.isEmpty()) {
            throw bodyBuilder.invalidInsn("builtin_cast target_type_name must not be empty");
        }

        var valueVariable = requireVariable(bodyBuilder, insn.valueId(), "value");
        var resultVariable = requireVariable(bodyBuilder, insn.resultId(), "result");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, valueVariable, "builtin_cast value");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVariable, "builtin_cast result");

        var registry = bodyBuilder.classRegistry();
        var resolvedTarget = registry.tryResolveDeclaredType(targetTypeName);
        if (resolvedTarget == null) {
            throw bodyBuilder.invalidInsn(
                    "builtin_cast target_type_name '" + targetTypeName + "' cannot be resolved for codegen"
            );
        }
        requireRuntimeBuiltinTarget(bodyBuilder, resolvedTarget);

        // Result slot must match the resolved target exactly (including full Array[T]/Dictionary[K,V] text).
        // Do not accept container covariance (e.g. Array[int] result slot typed as bare Array).
        if (!sameStaticType(resultVariable.type(), resolvedTarget)) {
            throw bodyBuilder.invalidInsn(
                    "builtin_cast result '" + resultVariable.id() + "' type '" +
                            resultVariable.type().getTypeName() + "' does not match target '" +
                            resolvedTarget.getTypeName() + "'"
            );
        }

        // Defensive re-check against the shared explicit-cast matrix (hand-written / legacy LIR).
        ExplicitCastDecision decision;
        try {
            decision = ExplicitCastSupport.classify(registry, valueVariable.type(), resolvedTarget);
        } catch (IllegalArgumentException ex) {
            throw bodyBuilder.invalidInsn("builtin_cast classifier rejected operand: " + ex.getMessage());
        }
        if (decision != ExplicitCastDecision.BUILTIN_RUNTIME_CAST) {
            throw bodyBuilder.invalidInsn(
                    "builtin_cast does not accept decision " + decision + " for source '" +
                            valueVariable.type().getTypeName() + "' -> '" + resolvedTarget.getTypeName() +
                            "' (identity/Variant/Object paths must use other insns)"
            );
        }

        emitVariantConstructCast(bodyBuilder, resultVariable, valueVariable, resolvedTarget);
    }

    private void emitVariantConstructCast(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable,
            @NotNull GdType targetType
    ) {
        // Pack non-Variant sources once; Variant sources pass through without a second pack.
        var sourceOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVariable, "bcast_src");
        var sourceArgCode = InsnGenSupport.renderArgumentCode(
                bodyBuilder,
                sourceOperand.variantValue(),
                "builtin_cast source"
        );

        var constructed = bodyBuilder.newTempVariable("bcast_result", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(constructed);
        // GDExtensionCallError / arg array are not GdTypes; reserve unique names only.
        var errorName = bodyBuilder.newTempVariable("bcast_error", GdBoolType.BOOL).name();
        var argArrayName = bodyBuilder.newTempVariable("bcast_args", GdBoolType.BOOL).name();
        var targetEnum = resolver.resolveVariantTypeEnumLiteral(bodyBuilder, targetType);

        // renderArgumentCode already yields a const Variant* form for both locals (&$v) and ref params ($v).
        bodyBuilder.appendLine("GDExtensionCallError " + errorName + " = { 0 };");
        bodyBuilder.appendLine(
                "const GDExtensionConstVariantPtr " + argArrayName + "[1] = { " +
                        sourceArgCode + " };"
        );
        bodyBuilder.appendLine(
                "godot_variant_construct(" + targetEnum + ", &" + constructed.name() + ", " +
                        argArrayName + ", 1, &" + errorName + ");"
        );
        bodyBuilder.appendLine("if (" + errorName + ".error != GDEXTENSION_CALL_OK) {");
        // Construct failed: destination was uninitialized — do not destroy `constructed`.
        InsnGenSupport.emitRuntimeFailureReturn(
                bodyBuilder,
                "godot_variant_construct failed for builtin_cast to '" + targetType.getTypeName() + "'",
                sourceOperand.tempVar()
        );
        bodyBuilder.appendLine("}");
        constructed.setInitialized(true);

        InsnGenSupport.unpackVariantAssign(
                bodyBuilder,
                bodyBuilder.targetOfVar(resultVariable),
                resultVariable.type(),
                constructed,
                "builtin_cast result"
        );
        bodyBuilder.destroyTempVar(constructed);
        if (sourceOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(sourceOperand.tempVar());
        }
    }

    private static void requireRuntimeBuiltinTarget(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull GdType target
    ) {
        if (target instanceof GdObjectType
                || target instanceof GdVariantType
                || target instanceof GdNilType) {
            throw bodyBuilder.invalidInsn(
                    "builtin_cast target must be a non-Object/non-Variant/non-Nil runtime builtin, got '" +
                            target.getTypeName() + "'"
            );
        }
        if (target instanceof GdArrayType || target instanceof GdDictionaryType) {
            // Base ARRAY/DICTIONARY (including parameterized) are valid construct targets.
            return;
        }
        if (target.getGdExtensionType() == null) {
            throw bodyBuilder.invalidInsn(
                    "builtin_cast target '" + target.getTypeName() + "' has no GDExtensionVariantType"
            );
        }
    }

    private static boolean sameStaticType(@NotNull GdType first, @NotNull GdType second) {
        return first == second
                || (first.getClass() == second.getClass()
                && first.getTypeName().equals(second.getTypeName()));
    }

    private static @NotNull LirVariable requireVariable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String variableId,
            @NotNull String role
    ) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("builtin_cast " + role + " variable not found: " + variableId);
        }
        return variable;
    }

}
