package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.TemplateLoader;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.exception.CodegenException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import freemarker.template.TemplateException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public abstract class TemplateInsnGen<Insn extends LirInstruction> implements CInsnGen<Insn> {
    protected abstract @NotNull String getTemplatePath();

    protected void validateInstruction(@NotNull CGenHelper helper,
                                       @NotNull LirClassDef clazz,
                                       @NotNull LirFunctionDef func,
                                       @NotNull LirBasicBlock block,
                                       int insnIndex,
                                       @NotNull Insn instruction) {

    }

    protected @NotNull Map<String, Object> getGenerationExtraData(@NotNull CGenHelper helper,
                                                                  @NotNull LirClassDef clazz,
                                                                  @NotNull LirFunctionDef func,
                                                                  @NotNull LirBasicBlock block,
                                                                  int insnIndex,
                                                                  @NotNull Insn instruction) {
        return Map.of();
    }

    @Override
    public @NotNull String generateCCode(@NotNull CGenHelper helper,
                                         @NotNull LirClassDef clazz,
                                         @NotNull LirFunctionDef func,
                                         @NotNull LirBasicBlock block,
                                         int insnIndex,
                                         @NotNull Insn instruction) {
        validateInstruction(helper, clazz, func, block, insnIndex, instruction);
        Map<String, Object> templateVariables;
        var blockExtraData = getGenerationExtraData(helper, clazz, func, block, insnIndex, instruction);
        if (blockExtraData.isEmpty()) {
            templateVariables = Map.of(
                    "helper", helper,
                    "func", func,
                    "block", block,
                    "insnIndex", insnIndex,
                    "insn", instruction,
                    "gen", this
            );
        } else {
            templateVariables = new HashMap<>(Map.of(
                    "helper", helper,
                    "func", func,
                    "block", block,
                    "insnIndex", insnIndex,
                    "insn", instruction,
                    "gen", this
            ));
            templateVariables.putAll(blockExtraData);
        }
        try {
            return TemplateLoader.renderFromClasspath(getTemplatePath(), templateVariables).trim();
        } catch (IOException | TemplateException e) {
            throw new CodegenException("Failed to generate C code for instruction: " + instruction, e);
        }
    }
}
