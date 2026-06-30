package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.TypeInstruction;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;

public final class PackUnpackVariantInsnGen implements CInsnGen<TypeInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.PACK_VARIANT, GdInstruction.UNPACK_VARIANT);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var func = bodyBuilder.func();
        if (instruction.resultId() == null) {
            throw bodyBuilder.invalidInsn("Instruction does not have a result variable ID");
        }
        var resultVar = func.getVariableById(Objects.requireNonNull(instruction.resultId()));
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + instruction.resultId() + "' not found in function");
        }
        var target = bodyBuilder.targetOfVar(resultVar);

        if (instruction instanceof UnpackVariantInsn unpackVariantInsn) {
            var variantVar = func.getVariableById(unpackVariantInsn.variantId());
            if (variantVar == null) {
                throw bodyBuilder.invalidInsn("Variant variable ID '" + unpackVariantInsn.variantId() + "' not found in function");
            }
            if (!(variantVar.type() instanceof GdVariantType)) {
                throw bodyBuilder.invalidInsn("Variant variable ID '" + unpackVariantInsn.variantId() +
                        "' is not of variant type, but " + variantVar.type().getTypeName());
            }

            InsnGenSupport.unpackVariantAssign(
                    bodyBuilder,
                    target,
                    resultVar.type(),
                    bodyBuilder.valueOfVar(variantVar),
                    "Variant unpack target"
            );
            return;
        }

        if (instruction instanceof PackVariantInsn packVariantInsn) {
            var valueVar = func.getVariableById(packVariantInsn.valueId());
            if (valueVar == null) {
                throw bodyBuilder.invalidInsn("Value variable ID '" + packVariantInsn.valueId() + "' not found in function");
            }

            InsnGenSupport.packVariantAssign(bodyBuilder, target, valueVar);
            return;
        }

        throw bodyBuilder.invalidInsn(
                "Unsupported instruction type for PackUnpackVariantInsnGen: " + instruction.getClass().getName()
        );
    }
}
