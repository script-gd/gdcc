package dev.superice.gdcc.backend;

import dev.superice.gdcc.lir.LirModule;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface Codegen {
    void prepare(@NotNull CodegenContext ctx, @NotNull LirModule module);

    List<GeneratedFile> generate();
}
