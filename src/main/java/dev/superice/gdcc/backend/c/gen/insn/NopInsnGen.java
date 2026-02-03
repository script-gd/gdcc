package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.NopInsn;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public final class NopInsnGen implements CInsnGen<NopInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.NOP);
    }

    @Override
    public @NotNull String generateCCode(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LirBasicBlock block, int insnIndex, @NotNull NopInsn instruction) {
        return "";
    }
}
