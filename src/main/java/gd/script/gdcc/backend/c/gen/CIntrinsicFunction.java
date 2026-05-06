package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.lir.LirVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Backend-owned intrinsic codegen entry.
///
/// `CallIntrinsicInsnGen` owns instruction parsing and variable resolution; implementations receive only
/// resolved slots so each intrinsic can focus on validating its own narrow signature and emitting code.
public interface CIntrinsicFunction {
    @NotNull String name();

    void generateCCode(@NotNull CBodyBuilder bodyBuilder,
                       @Nullable LirVariable resultVar,
                       @NotNull List<LirVariable> argVars);
}
