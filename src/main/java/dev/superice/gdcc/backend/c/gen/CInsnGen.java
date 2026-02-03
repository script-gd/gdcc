package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public interface CInsnGen<Insn extends LirInstruction> {
    @NotNull EnumSet<GdInstruction> getInsnOpcodes();

    @NotNull String generateCCode(@NotNull CGenHelper helper,
                                  @NotNull LirClassDef clazz,
                                  @NotNull LirFunctionDef func,
                                  @NotNull LirBasicBlock block,
                                  int insnIndex,
                                  @NotNull Insn instruction);
}
