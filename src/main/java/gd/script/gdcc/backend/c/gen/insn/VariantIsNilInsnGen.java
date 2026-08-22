package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.VariantIsNilInsn;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;

/// Lowers `variant_is_nil` as `godot_variant_get_type(&v) == GDEXTENSION_VARIANT_TYPE_NIL`.
///
/// The operand is already a Variant local. There is no dedicated `godot_variant_is_nil` API.
public final class VariantIsNilInsnGen implements CInsnGen<VariantIsNilInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.VARIANT_IS_NIL);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var variantVariable = bodyBuilder.func().getVariableById(insn.variantId());
        if (variantVariable == null) {
            throw bodyBuilder.invalidInsn("variant_is_nil variant variable not found: " + insn.variantId());
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, variantVariable, "variant_is_nil variant");
        if (!(variantVariable.type() instanceof GdVariantType)) {
            throw bodyBuilder.invalidInsn(
                    "variant_is_nil variant '" + variantVariable.id() + "' must be Variant, got '" +
                            variantVariable.type().getTypeName() + "'"
            );
        }
        var resultVariable = bodyBuilder.func().getVariableById(Objects.requireNonNull(insn.resultId()));
        if (resultVariable == null) {
            throw bodyBuilder.invalidInsn("variant_is_nil result variable not found: " + insn.resultId());
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVariable, "variant_is_nil result");
        if (!(resultVariable.type() instanceof GdBoolType)) {
            throw bodyBuilder.invalidInsn(
                    "variant_is_nil result '" + resultVariable.id() + "' must be bool, got '" +
                            resultVariable.type().getTypeName() + "'"
            );
        }
        var variantCode = InsnGenSupport.renderArgumentCode(
                bodyBuilder,
                bodyBuilder.valueOfVar(variantVariable),
                "variant_is_nil"
        );
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVariable),
                "(godot_variant_get_type(" + variantCode + ") == GDEXTENSION_VARIANT_TYPE_NIL)",
                GdBoolType.BOOL
        );
    }
}
