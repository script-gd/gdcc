package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.IndexingInstruction;
import dev.superice.gdcc.lir.insn.VariantGetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantGetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/// C code generator for indexing load instructions:
/// - variant_get
/// - variant_get_keyed
/// - variant_get_named
/// - variant_get_indexed
public final class IndexLoadInsnGen implements CInsnGen<IndexingInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.VARIANT_GET,
                GdInstruction.VARIANT_GET_KEYED,
                GdInstruction.VARIANT_GET_NAMED,
                GdInstruction.VARIANT_GET_INDEXED
        );
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        switch (instruction) {
            case VariantGetInsn insn -> emitVariantGet(bodyBuilder, insn);
            case VariantGetKeyedInsn insn -> emitVariantGetKeyed(bodyBuilder, insn);
            case VariantGetNamedInsn insn -> emitVariantGetNamed(bodyBuilder, insn);
            case VariantGetIndexedInsn insn -> emitVariantGetIndexed(bodyBuilder, insn);
            default -> throw bodyBuilder.invalidInsn("Unsupported index load instruction: " +
                    instruction.getClass().getSimpleName());
        }
    }

    private void emitVariantGet(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantGetInsn insn) {
        var resultVar = resolveRequiredResultVariable(bodyBuilder, insn.resultId());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.variantId(), "self");
        var keyVar = resolveOperandVariable(bodyBuilder, insn.keyId(), "key");

        emitVariantGetByVariantKey(
                bodyBuilder,
                insn.opcode().opcode(),
                "godot_variant_get",
                resultVar,
                selfVar,
                keyVar
        );
    }

    private void emitVariantGetKeyed(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantGetKeyedInsn insn) {
        var resultVar = resolveRequiredResultVariable(bodyBuilder, insn.resultId());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.keyedVariantId(), "self");
        var keyVar = resolveOperandVariable(bodyBuilder, insn.keyId(), "key");

        emitVariantGetByVariantKey(
                bodyBuilder,
                insn.opcode().opcode(),
                "godot_variant_get_keyed",
                resultVar,
                selfVar,
                keyVar
        );
    }

    private void emitVariantGetByVariantKey(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull String insnName,
                                            @NotNull String cFunctionName,
                                            @NotNull LirVariable resultVar,
                                            @NotNull LirVariable selfVar,
                                            @NotNull LirVariable keyVar) {
        var selfOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, selfVar, "idx_self_variant");
        var keyOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, keyVar, "idx_key_variant");
        var selfArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, selfOperand.variantValue(), "index load instruction");
        var keyArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, keyOperand.variantValue(), "index load instruction");
        var retTemp = bodyBuilder.newTempVariable("idx_ret", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(retTemp);
        var validFlag = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);

        bodyBuilder.appendLine(
                cFunctionName + "(" +
                        selfArgCode + ", " +
                        keyArgCode + ", " +
                        "&" + retTemp.name() + ", " +
                        "&" + validFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"" + insnName +
                        " failed: self=$" + selfVar.id() +
                        ", key=$" + keyVar.id() +
                        ", result=$" + resultVar.id() +
                        "\", __func__, __FILE__, __LINE__);"
        );
        emitInvalidReturn(bodyBuilder, retTemp, keyOperand.tempVar(), selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        retTemp.setInitialized(true);
        if (keyOperand.tempVar() != null) {
            keyOperand.tempVar().setInitialized(true);
        }
        if (selfOperand.tempVar() != null) {
            selfOperand.tempVar().setInitialized(true);
        }

        emitAssignResultFromVariant(bodyBuilder, resultVar, retTemp);
        bodyBuilder.destroyTempVar(retTemp);
        if (keyOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(keyOperand.tempVar());
        }
        if (selfOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(selfOperand.tempVar());
        }
    }

    private void emitVariantGetNamed(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantGetNamedInsn insn) {
        var resultVar = resolveRequiredResultVariable(bodyBuilder, insn.resultId());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.namedVariantId(), "self");
        var nameVar = resolveStringNameOperandVariable(bodyBuilder, insn.nameId());
        var selfOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, selfVar, "idx_self_variant");
        var selfArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, selfOperand.variantValue(), "index load instruction");
        var retTemp = bodyBuilder.newTempVariable("idx_ret", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(retTemp);
        var validFlag = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);
        var keyArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, bodyBuilder.valueOfVar(nameVar), "index load instruction");

        bodyBuilder.appendLine(
                "godot_variant_get_named(" +
                        selfArgCode + ", " +
                        keyArgCode + ", " +
                        "&" + retTemp.name() + ", " +
                        "&" + validFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_get_named failed: self=$" + selfVar.id() +
                        ", name=$" + nameVar.id() +
                        ", result=$" + resultVar.id() +
                        "\", __func__, __FILE__, __LINE__);"
        );
        emitInvalidReturn(bodyBuilder, retTemp, null, selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        retTemp.setInitialized(true);
        if (selfOperand.tempVar() != null) {
            selfOperand.tempVar().setInitialized(true);
        }

        emitAssignResultFromVariant(bodyBuilder, resultVar, retTemp);
        bodyBuilder.destroyTempVar(retTemp);
        if (selfOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(selfOperand.tempVar());
        }
    }

    private void emitVariantGetIndexed(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantGetIndexedInsn insn) {
        var resultVar = resolveRequiredResultVariable(bodyBuilder, insn.resultId());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.variantId(), "self");
        var indexVar = resolveIndexedOperandVariable(bodyBuilder, insn.indexId());
        var selfOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, selfVar, "idx_self_variant");
        var selfArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, selfOperand.variantValue(), "index load instruction");
        var indexArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, bodyBuilder.valueOfVar(indexVar), "index load instruction");
        var retTemp = bodyBuilder.newTempVariable("idx_ret", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(retTemp);
        var validFlag = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);
        var oobFlag = bodyBuilder.newTempVariable("idx_oob", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(oobFlag);

        bodyBuilder.appendLine(
                "godot_variant_get_indexed(" +
                        selfArgCode + ", " +
                        "(GDExtensionInt)" + indexArgCode + ", " +
                        "&" + retTemp.name() + ", " +
                        "&" + validFlag.name() + ", " +
                        "&" + oobFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_get_indexed failed: self=$" + selfVar.id() +
                        ", index=$" + indexVar.id() +
                        ", result=$" + resultVar.id() +
                        "\", __func__, __FILE__, __LINE__);"
        );
        emitInvalidReturn(bodyBuilder, retTemp, null, selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        retTemp.setInitialized(true);
        if (selfOperand.tempVar() != null) {
            selfOperand.tempVar().setInitialized(true);
        }

        bodyBuilder.appendLine("if (" + oobFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_get_indexed index out of bounds: index=$" +
                        indexVar.id() + "\", __func__, __FILE__, __LINE__);"
        );
        emitOobReturn(bodyBuilder, retTemp, selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        retTemp.setInitialized(true);
        if (selfOperand.tempVar() != null) {
            selfOperand.tempVar().setInitialized(true);
        }

        emitAssignResultFromVariant(bodyBuilder, resultVar, retTemp);
        bodyBuilder.destroyTempVar(retTemp);
        if (selfOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(selfOperand.tempVar());
        }
    }

    private void emitAssignResultFromVariant(@NotNull CBodyBuilder bodyBuilder,
                                             @NotNull LirVariable resultVar,
                                             @NotNull CBodyBuilder.TempVar retTemp) {
        if (resultVar.type() instanceof GdVariantType) {
            bodyBuilder.callAssign(
                    bodyBuilder.targetOfVar(resultVar),
                    "godot_new_Variant_with_Variant",
                    GdVariantType.VARIANT,
                    List.of(retTemp)
            );
            return;
        }

        var unpackFunctionName = bodyBuilder.helper().renderUnpackFunctionName(resultVar.type());
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(resultVar),
                unpackFunctionName,
                resultVar.type(),
                List.of(retTemp)
        );
    }

    private void emitInvalidReturn(@NotNull CBodyBuilder bodyBuilder,
                                   @NotNull CBodyBuilder.TempVar retTemp,
                                   @Nullable CBodyBuilder.TempVar keyTemp,
                                   @Nullable CBodyBuilder.TempVar selfTemp) {
        bodyBuilder.assignExpr(retTemp, "godot_new_Variant_nil()", GdVariantType.VARIANT);
        bodyBuilder.destroyTempVar(retTemp);
        if (keyTemp != null) {
            bodyBuilder.destroyTempVar(keyTemp);
        }
        if (selfTemp != null) {
            bodyBuilder.destroyTempVar(selfTemp);
        }
        bodyBuilder.returnDefault();
    }

    private void emitOobReturn(@NotNull CBodyBuilder bodyBuilder,
                               @NotNull CBodyBuilder.TempVar retTemp,
                               @Nullable CBodyBuilder.TempVar selfTemp) {
        bodyBuilder.destroyTempVar(retTemp);
        if (selfTemp != null) {
            bodyBuilder.destroyTempVar(selfTemp);
        }
        bodyBuilder.returnDefault();
    }

    private @NotNull LirVariable resolveRequiredResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                                               @Nullable String resultId) {
        if (resultId == null || resultId.isBlank()) {
            throw bodyBuilder.invalidInsn("Index load instruction missing required result variable ID");
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

    private @NotNull LirVariable resolveOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                        @NotNull String variableId,
                                                        @NotNull String role) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("Index load " + role + " operand variable ID '" + variableId +
                    "' not found in function");
        }
        return variable;
    }

    private @NotNull LirVariable resolveIndexedOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                               @NotNull String indexId) {
        var indexVar = resolveOperandVariable(bodyBuilder, indexId, "index");
        if (!(indexVar.type() instanceof GdIntType)) {
            throw bodyBuilder.invalidInsn("Index load index operand variable ID '" + indexId +
                    "' must be int, got '" + indexVar.type().getTypeName() + "'");
        }
        return indexVar;
    }

    private @NotNull LirVariable resolveStringNameOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                                  @NotNull String nameId) {
        var nameVar = resolveOperandVariable(bodyBuilder, nameId, "name");
        if (!(nameVar.type() instanceof GdStringNameType)) {
            throw bodyBuilder.invalidInsn("Index load name operand variable ID '" + nameId +
                    "' must be StringName, got '" + nameVar.type().getTypeName() + "'");
        }
        return nameVar;
    }

}
