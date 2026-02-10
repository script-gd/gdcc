package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.DestructInsn;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Map;

public final class DestructInsnGen extends TemplateInsnGen<DestructInsn> {
    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/destruct.ftl";
    }

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.DESTRUCT);
    }

    @Override
    protected @NotNull Map<String, Object> getGenerationExtraData(@NotNull CGenHelper helper,
                                                                  @NotNull LirClassDef clazz,
                                                                  @NotNull LirFunctionDef func,
                                                                  @NotNull LirBasicBlock block,
                                                                  int insnIndex,
                                                                  @NotNull DestructInsn instruction) {
        var variable = func.getVariableById(instruction.variableId());
        if (variable == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.toString(),
                    "Variable ID '" + instruction.variableId() + "' not found in function");
        }
        var genMode = switch (variable.type()) {
            case GdObjectType objectType -> {
                var registry = helper.context().classRegistry();
                var gdcc = objectType.checkGdccType(registry);
                if (registry.checkAssignable(objectType, new GdObjectType("RefCounted"))) {
                    if (gdcc) {
                        yield "ref_counted_gdcc";
                    } else {
                        yield "ref_counted";
                    }
                } else if (objectType.checkEngineType(registry)) {
                    yield "engine_object";
                } else if (gdcc) {
                    yield "gdcc_object";
                } else {
                    yield "general_object";
                }
            }
            case GdVariantType _ -> "variant";
            case GdStringLikeType _, GdMetaType _, GdContainerType _ -> "str_meta_container";
            default -> "primitive";
        };
        return Map.of("genMode", genMode, "typeName", variable.type().getTypeName());
    }

    @Override
    protected Map<String, Object> validateInstruction(@NotNull CGenHelper helper,
                                                      @NotNull LirClassDef clazz,
                                                      @NotNull LirFunctionDef func,
                                                      @NotNull LirBasicBlock block,
                                                      int insnIndex,
                                                      @NotNull DestructInsn instruction) {
        var variable = func.getVariableById(instruction.variableId());
        if (variable == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.toString(),
                    "Variable ID '" + instruction.variableId() + "' not found in function");
        }
        switch (variable.type()) {
            case GdVoidType _ ->
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.toString(),
                            "Cannot destruct variable of type " + variable.type().getTypeName());
            default -> {
            }
        }
        return Map.of();
    }
}
