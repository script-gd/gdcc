package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.insn.StoreStaticInsn;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public final class StoreStaticInsnGen implements CInsnGen<StoreStaticInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.STORE_STATIC);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        throw bodyBuilder.invalidInsn(
                "Unsupported static store: 'store_static' is not allowed in current backend"
        );
    }
}
