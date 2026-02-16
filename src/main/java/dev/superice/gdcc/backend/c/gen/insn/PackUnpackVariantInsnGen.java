package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.TypeInstruction;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
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
        var helper = bodyBuilder.helper();

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

            var unpackFunc = helper.renderUnpackFunctionName(resultVar.type());
            bodyBuilder.callAssign(target, unpackFunc, resultVar.type(), List.of(bodyBuilder.valueOfVar(variantVar)));
            return;
        }

        if (instruction instanceof PackVariantInsn packVariantInsn) {
            var valueVar = func.getVariableById(packVariantInsn.valueId());
            if (valueVar == null) {
                throw bodyBuilder.invalidInsn("Value variable ID '" + packVariantInsn.valueId() + "' not found in function");
            }

            var packFunc = helper.renderPackFunctionName(valueVar.type());
            bodyBuilder.callAssign(target, packFunc, GdVariantType.VARIANT, List.of(bodyBuilder.valueOfVar(valueVar)));
            return;
        }

        throw bodyBuilder.invalidInsn(
                "Unsupported instruction type for PackUnpackVariantInsnGen: " + instruction.getClass().getName()
        );
    }
}
