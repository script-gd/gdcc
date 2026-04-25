package dev.superice.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface CCompiler {
    CCompileResult compile(
            @NotNull Path projectDir,
            @NotNull List<Path> includeDirs,
            @NotNull List<Path> cFiles,
            @NotNull String outputBaseName,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) throws IOException;
}
