package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.GetVariantTypeInsn;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/// Lowers `get_variant_type` as `godot_variant_get_type(&v)`.
///
/// The operand is already a Variant local. The C return is `GDExtensionVariantType`, stored as
/// `godot_int` with the same ordinal as `GdExtensionTypeEnum`.
public final class GetVariantTypeInsnGen implements CInsnGen<GetVariantTypeInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.GET_VARIANT_TYPE);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var variantVariable = bodyBuilder.func().getVariableById(insn.variantId());
        if (variantVariable == null) {
            throw bodyBuilder.invalidInsn("get_variant_type variant variable not found: " + insn.variantId());
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, variantVariable, "get_variant_type variant");
        if (!(variantVariable.type() instanceof GdVariantType)) {
            throw bodyBuilder.invalidInsn(
                    "get_variant_type variant '" + variantVariable.id() + "' must be Variant, got '" +
                            variantVariable.type().getTypeName() + "'"
            );
        }
        var resultId = insn.resultId();
        if (resultId == null) {
            throw bodyBuilder.invalidInsn("get_variant_type result id is required");
        }
        var resultVariable = bodyBuilder.func().getVariableById(resultId);
        if (resultVariable == null) {
            throw bodyBuilder.invalidInsn("get_variant_type result variable not found: " + resultId);
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVariable, "get_variant_type result");
        if (!(resultVariable.type() instanceof GdIntType)) {
            throw bodyBuilder.invalidInsn(
                    "get_variant_type result '" + resultVariable.id() + "' must be int, got '" +
                            resultVariable.type().getTypeName() + "'"
            );
        }
        var variantCode = InsnGenSupport.renderArgumentCode(
                bodyBuilder,
                bodyBuilder.valueOfVar(variantVariable),
                "get_variant_type"
        );
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVariable),
                "godot_variant_get_type(" + variantCode + ")",
                GdIntType.INT
        );
    }
}
