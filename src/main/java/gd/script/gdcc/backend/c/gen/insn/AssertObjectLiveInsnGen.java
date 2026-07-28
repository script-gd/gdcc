package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.AssertObjectLiveInsn;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/// Lowers `assert_object_live` to an explicit hard-fail guard.
/// The guard stays visible in generated C so later optimization passes can model its error edge.
public final class AssertObjectLiveInsnGen implements CInsnGen<AssertObjectLiveInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.ASSERT_OBJECT_LIVE);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var variable = bodyBuilder.func().getVariableById(insn.objectId());
        if (variable == null) {
            throw bodyBuilder.invalidInsn("assert_object_live target variable not found: " + insn.objectId());
        }
        bodyBuilder.emitAssertObjectLiveGuard(variable);
    }
}
