package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.IndexingInstruction;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.lir.insn.VariantSetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdMetaType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdNodePathType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedArrayType;
import dev.superice.gdcc.type.GdPrimitiveType;
import dev.superice.gdcc.type.GdRidType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVectorType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// C code generator for indexing store instructions:
/// - variant_set
/// - variant_set_keyed
/// - variant_set_named
/// - variant_set_indexed
public final class IndexStoreInsnGen implements CInsnGen<IndexingInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.VARIANT_SET,
                GdInstruction.VARIANT_SET_KEYED,
                GdInstruction.VARIANT_SET_NAMED,
                GdInstruction.VARIANT_SET_INDEXED
        );
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        switch (instruction) {
            case VariantSetInsn insn -> emitVariantSet(bodyBuilder, insn);
            case VariantSetKeyedInsn insn -> emitVariantSetKeyed(bodyBuilder, insn);
            case VariantSetNamedInsn insn -> emitVariantSetNamed(bodyBuilder, insn);
            case VariantSetIndexedInsn insn -> emitVariantSetIndexed(bodyBuilder, insn);
            default -> throw bodyBuilder.invalidInsn("Unsupported index store instruction: " +
                    instruction.getClass().getSimpleName());
        }
    }

    private void emitVariantSet(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantSetInsn insn) {
        ensureNoResultVariable(bodyBuilder, insn.resultId(), insn.opcode().opcode());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.variantId(), "self");
        var keyVar = resolveOperandVariable(bodyBuilder, insn.keyId(), "key");
        var valueVar = resolveOperandVariable(bodyBuilder, insn.valueId(), "value");

        var selfOperand = materializeSelfOperand(bodyBuilder, selfVar, SelfMode.VARIANT_SET);
        var keyOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, keyVar, "idx_key_variant");
        var valueOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVar, "idx_val_variant");

        emitVariantSetByVariantKey(
                bodyBuilder,
                insn.opcode().opcode(),
                "godot_variant_set",
                selfVar,
                keyVar,
                valueVar,
                selfOperand,
                keyOperand,
                valueOperand
        );
    }

    private void emitVariantSetKeyed(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantSetKeyedInsn insn) {
        ensureNoResultVariable(bodyBuilder, insn.resultId(), insn.opcode().opcode());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.keyedVariantId(), "self");
        var keyVar = resolveOperandVariable(bodyBuilder, insn.keyId(), "key");
        var valueVar = resolveOperandVariable(bodyBuilder, insn.valueId(), "value");

        var selfOperand = materializeSelfOperand(bodyBuilder, selfVar, SelfMode.VARIANT_SET_KEYED);
        var keyOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, keyVar, "idx_key_variant");
        var valueOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVar, "idx_val_variant");

        emitVariantSetByVariantKey(
                bodyBuilder,
                insn.opcode().opcode(),
                "godot_variant_set_keyed",
                selfVar,
                keyVar,
                valueVar,
                selfOperand,
                keyOperand,
                valueOperand
        );
    }

    private void emitVariantSetByVariantKey(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull String insnName,
                                            @NotNull String cFunctionName,
                                            @NotNull LirVariable selfVar,
                                            @NotNull LirVariable keyVar,
                                            @NotNull LirVariable valueVar,
                                            @NotNull SelfOperand selfOperand,
                                            @NotNull InsnGenSupport.VariantOperand keyOperand,
                                            @NotNull InsnGenSupport.VariantOperand valueOperand) {
        var selfArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, selfOperand.variantValue(), "index store instruction");
        var keyArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, keyOperand.variantValue(), "index store instruction");
        var valueArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, valueOperand.variantValue(), "index store instruction");
        var validFlag = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);

        bodyBuilder.appendLine(
                cFunctionName + "(" +
                        selfArgCode + ", " +
                        keyArgCode + ", " +
                        valueArgCode + ", " +
                        "&" + validFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"" + insnName +
                        " failed: self=$" + selfVar.id() +
                        ", key=$" + keyVar.id() +
                        ", value=$" + valueVar.id() +
                        "\", __func__, __FILE__, __LINE__);"
        );
        emitFailureReturn(bodyBuilder, valueOperand.tempVar(), keyOperand.tempVar(), selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        restoreInitialized(valueOperand.tempVar(), keyOperand.tempVar(), selfOperand.tempVar());

        emitSelfWritebackIfNeeded(bodyBuilder, selfVar, selfOperand);

        if (valueOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(valueOperand.tempVar());
        }
        if (keyOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(keyOperand.tempVar());
        }
        if (selfOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(selfOperand.tempVar());
        }
    }

    private void emitVariantSetNamed(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantSetNamedInsn insn) {
        ensureNoResultVariable(bodyBuilder, insn.resultId(), insn.opcode().opcode());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.namedVariantId(), "self");
        var nameVar = resolveStringNameOperandVariable(bodyBuilder, insn.nameId());
        var valueVar = resolveOperandVariable(bodyBuilder, insn.valueId(), "value");

        var selfOperand = materializeSelfOperand(bodyBuilder, selfVar, SelfMode.VARIANT_SET_NAMED);
        var valueOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVar, "idx_val_variant");
        var selfArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, selfOperand.variantValue(), "index store instruction");
        var keyArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, bodyBuilder.valueOfVar(nameVar), "index store instruction");
        var valueArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, valueOperand.variantValue(), "index store instruction");
        var validFlag = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);

        bodyBuilder.appendLine(
                "godot_variant_set_named(" +
                        selfArgCode + ", " +
                        keyArgCode + ", " +
                        valueArgCode + ", " +
                        "&" + validFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_set_named failed: self=$" + selfVar.id() +
                        ", name=$" + nameVar.id() +
                        ", value=$" + valueVar.id() +
                        "\", __func__, __FILE__, __LINE__);"
        );
        emitFailureReturn(bodyBuilder, valueOperand.tempVar(), selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        restoreInitialized(valueOperand.tempVar(), selfOperand.tempVar());

        emitSelfWritebackIfNeeded(bodyBuilder, selfVar, selfOperand);

        if (valueOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(valueOperand.tempVar());
        }
        if (selfOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(selfOperand.tempVar());
        }
    }

    private void emitVariantSetIndexed(@NotNull CBodyBuilder bodyBuilder, @NotNull VariantSetIndexedInsn insn) {
        ensureNoResultVariable(bodyBuilder, insn.resultId(), insn.opcode().opcode());
        var selfVar = resolveOperandVariable(bodyBuilder, insn.variantId(), "self");
        var indexVar = resolveIndexedOperandVariable(bodyBuilder, insn.indexId());
        var valueVar = resolveOperandVariable(bodyBuilder, insn.valueId(), "value");

        var selfOperand = materializeSelfOperand(bodyBuilder, selfVar, SelfMode.VARIANT_SET_INDEXED);
        var valueOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVar, "idx_val_variant");
        var selfArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, selfOperand.variantValue(), "index store instruction");
        var indexArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, bodyBuilder.valueOfVar(indexVar), "index store instruction");
        var valueArgCode = InsnGenSupport.renderArgumentCode(bodyBuilder, valueOperand.variantValue(), "index store instruction");
        var validFlag = bodyBuilder.newTempVariable("idx_valid", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(validFlag);
        var oobFlag = bodyBuilder.newTempVariable("idx_oob", GdBoolType.BOOL, "false");
        bodyBuilder.declareTempVar(oobFlag);

        bodyBuilder.appendLine(
                "godot_variant_set_indexed(" +
                        selfArgCode + ", " +
                        "(GDExtensionInt)" + indexArgCode + ", " +
                        valueArgCode + ", " +
                        "&" + validFlag.name() + ", " +
                        "&" + oobFlag.name() +
                        ");"
        );
        bodyBuilder.appendLine("if (!" + validFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_set_indexed failed: self=$" + selfVar.id() +
                        ", index=$" + indexVar.id() +
                        ", value=$" + valueVar.id() +
                        "\", __func__, __FILE__, __LINE__);"
        );
        emitFailureReturn(bodyBuilder, valueOperand.tempVar(), selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        restoreInitialized(valueOperand.tempVar(), selfOperand.tempVar());

        bodyBuilder.appendLine("if (" + oobFlag.name() + ") {");
        bodyBuilder.appendLine(
                "GDCC_PRINT_RUNTIME_ERROR(\"variant_set_indexed index out of bounds: index=$" +
                        indexVar.id() + "\", __func__, __FILE__, __LINE__);"
        );
        emitFailureReturn(bodyBuilder, valueOperand.tempVar(), selfOperand.tempVar());
        bodyBuilder.appendLine("}");
        restoreInitialized(valueOperand.tempVar(), selfOperand.tempVar());

        emitSelfWritebackIfNeeded(bodyBuilder, selfVar, selfOperand);

        if (valueOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(valueOperand.tempVar());
        }
        if (selfOperand.tempVar() != null) {
            bodyBuilder.destroyTempVar(selfOperand.tempVar());
        }
    }

    private void emitSelfWritebackIfNeeded(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull LirVariable selfVar,
                                           @NotNull SelfOperand selfOperand) {
        if (!selfOperand.requiresWriteback()) {
            return;
        }
        if (selfOperand.tempVar() == null) {
            throw bodyBuilder.invalidInsn("Internal error: missing self temp variant for writeback");
        }
        var unpackFunctionName = bodyBuilder.helper().renderUnpackFunctionName(selfVar.type());
        bodyBuilder.callAssign(
                bodyBuilder.targetOfVar(selfVar),
                unpackFunctionName,
                selfVar.type(),
                List.of(selfOperand.tempVar())
        );
    }

    private @NotNull SelfOperand materializeSelfOperand(@NotNull CBodyBuilder bodyBuilder,
                                                        @NotNull LirVariable selfVar,
                                                        @NotNull SelfMode selfMode) {
        if (selfVar.type() instanceof GdVariantType) {
            return new SelfOperand(bodyBuilder.valueOfVar(selfVar), null, false);
        }
        var selfStrategy = resolveSelfStrategy(bodyBuilder, selfVar, selfMode);
        if (selfVar.ref() && selfStrategy == SelfStrategy.VALUE_SEMANTIC) {
            throw bodyBuilder.invalidInsn("Index store self operand variable ID '" + selfVar.id() +
                    "' is a reference and requires writeback, which is not supported for type '" +
                    selfVar.type().getTypeName() + "'");
        }
        var selfVariantTemp = bodyBuilder.newTempVariable("idx_self_variant", GdVariantType.VARIANT);
        bodyBuilder.declareTempVar(selfVariantTemp);
        InsnGenSupport.packVariantAssign(bodyBuilder, selfVariantTemp, selfVar);
        return new SelfOperand(
                selfVariantTemp,
                selfVariantTemp,
                selfStrategy == SelfStrategy.VALUE_SEMANTIC
        );
    }

    private @NotNull SelfStrategy resolveSelfStrategy(@NotNull CBodyBuilder bodyBuilder,
                                                      @NotNull LirVariable selfVar,
                                                      @NotNull SelfMode selfMode) {
        var selfType = selfVar.type();
        if (isUnsupportedSetSelfType(selfType)) {
            throw bodyBuilder.invalidInsn("Index store instruction '" + selfMode.insnName +
                    "' self operand variable ID '" + selfVar.id() + "' has unsupported type '" +
                    selfType.getTypeName() + "'");
        }

        return switch (selfMode) {
            case VARIANT_SET -> isReferenceSemanticSelfType(selfType)
                    ? SelfStrategy.REFERENCE_SEMANTIC
                    : SelfStrategy.VALUE_SEMANTIC;
            case VARIANT_SET_KEYED -> {
                if (selfType instanceof GdObjectType || selfType instanceof GdDictionaryType) {
                    yield SelfStrategy.REFERENCE_SEMANTIC;
                }
                throw bodyBuilder.invalidInsn("variant_set_keyed self operand variable ID '" + selfVar.id() +
                        "' must be Variant/Object/Dictionary, got '" + selfType.getTypeName() + "'");
            }
            case VARIANT_SET_NAMED -> {
                if (selfType instanceof GdObjectType || selfType instanceof GdDictionaryType) {
                    yield SelfStrategy.REFERENCE_SEMANTIC;
                }
                if (isNamedValueSemanticSelfType(selfType)) {
                    yield SelfStrategy.VALUE_SEMANTIC;
                }
                throw bodyBuilder.invalidInsn("variant_set_named self operand variable ID '" + selfVar.id() +
                        "' is not supported for named set, got '" + selfType.getTypeName() + "'");
            }
            case VARIANT_SET_INDEXED -> {
                if (isIndexedReferenceSemanticSelfType(selfType)) {
                    yield SelfStrategy.REFERENCE_SEMANTIC;
                }
                if (isIndexedValueSemanticSelfType(selfType)) {
                    yield SelfStrategy.VALUE_SEMANTIC;
                }
                throw bodyBuilder.invalidInsn("variant_set_indexed self operand variable ID '" + selfVar.id() +
                        "' is not supported for indexed set, got '" + selfType.getTypeName() + "'");
            }
        };
    }

    private boolean isUnsupportedSetSelfType(@NotNull GdType type) {
        return type instanceof GdPrimitiveType ||
                type instanceof GdNilType ||
                type instanceof GdStringNameType ||
                type instanceof GdNodePathType ||
                type instanceof GdRidType ||
                type instanceof GdMetaType ||
                type instanceof GdVoidType;
    }

    private boolean isReferenceSemanticSelfType(@NotNull GdType type) {
        return type instanceof GdObjectType ||
                type instanceof GdArrayType ||
                type instanceof GdDictionaryType;
    }

    private boolean isNamedValueSemanticSelfType(@NotNull GdType type) {
        return type instanceof GdStringType || type instanceof GdVectorType;
    }

    private boolean isIndexedReferenceSemanticSelfType(@NotNull GdType type) {
        return type instanceof GdArrayType ||
                type instanceof GdDictionaryType;
    }

    private boolean isIndexedValueSemanticSelfType(@NotNull GdType type) {
        return type instanceof GdStringType ||
                type instanceof GdVectorType ||
                type instanceof GdPackedArrayType;
    }

    private void emitFailureReturn(@NotNull CBodyBuilder bodyBuilder,
                                   @Nullable CBodyBuilder.TempVar... tempsToDestroy) {
        if (tempsToDestroy != null) {
            for (var temp : tempsToDestroy) {
                if (temp != null) {
                    bodyBuilder.destroyTempVar(temp);
                }
            }
        }
        bodyBuilder.returnDefault();
    }

    private void restoreInitialized(@Nullable CBodyBuilder.TempVar... tempsToRestore) {
        for (var temp : tempsToRestore) {
            if (temp != null) {
                temp.setInitialized(true);
            }
        }
    }

    private void ensureNoResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                        @Nullable String resultId,
                                        @NotNull String insnName) {
        if (resultId == null || resultId.isBlank()) {
            return;
        }
        throw bodyBuilder.invalidInsn("Index store instruction '" + insnName +
                "' must not have result variable ID, but got '" + resultId + "'");
    }

    private @NotNull LirVariable resolveOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                        @NotNull String variableId,
                                                        @NotNull String role) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("Index store " + role + " operand variable ID '" + variableId +
                    "' not found in function");
        }
        return variable;
    }

    private @NotNull LirVariable resolveStringNameOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                                  @NotNull String nameId) {
        var nameVar = resolveOperandVariable(bodyBuilder, nameId, "name");
        if (!(nameVar.type() instanceof GdStringNameType)) {
            throw bodyBuilder.invalidInsn("Index store name operand variable ID '" + nameId +
                    "' must be StringName, got '" + nameVar.type().getTypeName() + "'");
        }
        return nameVar;
    }

    private @NotNull LirVariable resolveIndexedOperandVariable(@NotNull CBodyBuilder bodyBuilder,
                                                               @NotNull String indexId) {
        var indexVar = resolveOperandVariable(bodyBuilder, indexId, "index");
        if (!(indexVar.type() instanceof GdIntType)) {
            throw bodyBuilder.invalidInsn("Index store index operand variable ID '" + indexId +
                    "' must be int, got '" + indexVar.type().getTypeName() + "'");
        }
        return indexVar;
    }

    private enum SelfMode {
        VARIANT_SET("variant_set"),
        VARIANT_SET_KEYED("variant_set_keyed"),
        VARIANT_SET_NAMED("variant_set_named"),
        VARIANT_SET_INDEXED("variant_set_indexed");

        private final @NotNull String insnName;

        SelfMode(@NotNull String insnName) {
            this.insnName = insnName;
        }
    }

    private enum SelfStrategy {
        REFERENCE_SEMANTIC,
        VALUE_SEMANTIC
    }

    private record SelfOperand(@NotNull CBodyBuilder.ValueRef variantValue,
                               @Nullable CBodyBuilder.TempVar tempVar,
                               boolean requiresWriteback) {
        private SelfOperand {
            Objects.requireNonNull(variantValue);
        }
    }

}
