package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.LifecycleProvenance;
import org.jetbrains.annotations.NotNull;

public interface LifecycleInstruction extends ConstructionInstruction {
    @NotNull LifecycleProvenance getProvenance();
}
