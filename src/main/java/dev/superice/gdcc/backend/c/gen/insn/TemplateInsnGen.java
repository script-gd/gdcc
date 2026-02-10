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

    protected Map<String, Object> validateInstruction(@NotNull CGenHelper helper,
                                                      @NotNull LirClassDef clazz,
                                                      @NotNull LirFunctionDef func,
                                                      @NotNull LirBasicBlock block,
                                                      int insnIndex,
                                                      @NotNull Insn instruction) {
        return Map.of();
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
        var validateData = validateInstruction(helper, clazz, func, block, insnIndex, instruction);
        Map<String, Object> templateVariables= new HashMap<>(Map.of(
                "helper", helper,
                "func", func,
                "block", block,
                "insnIndex", insnIndex,
                "insn", instruction,
                "gen", this
        ));
        if (!validateData.isEmpty()) {
            templateVariables.putAll(validateData);
        }
        var blockExtraData = getGenerationExtraData(helper, clazz, func, block, insnIndex, instruction);
        if (!blockExtraData.isEmpty()) {
            templateVariables.putAll(blockExtraData);
        }
        try {
            return TemplateLoader.renderFromClasspath(getTemplatePath(), templateVariables).trim();
        } catch (IOException | TemplateException e) {
            throw new CodegenException("Failed to generate C code for instruction: " + instruction, e);
        }
    }
}
