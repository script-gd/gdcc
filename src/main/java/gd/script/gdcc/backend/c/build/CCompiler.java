package gd.script.gdcc.backend.c.build;

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

    /// Symbol-stripping shape: backends that cannot strip (or never emit debug info) keep their
    /// historical behavior by falling through to the base method; only [ZigCcCompiler] acts on
    /// `stripNativeSymbols`.
    default CCompileResult compile(
            @NotNull Path projectDir,
            @NotNull List<Path> includeDirs,
            @NotNull List<Path> cFiles,
            @NotNull String outputBaseName,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform,
            boolean stripNativeSymbols
    ) throws IOException {
        return compile(projectDir, includeDirs, cFiles, outputBaseName, optimizationLevel, targetPlatform);
    }
}
