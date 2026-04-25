package dev.superice.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Full C backend build result after codegen files have been written and the native compiler has run.
public record CBuildResult(
        boolean success,
        @NotNull String buildLog,
        @NotNull List<Path> artifacts,
        @NotNull List<Path> generatedFiles
) {
    public CBuildResult {
        Objects.requireNonNull(buildLog, "buildLog must not be null");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
        generatedFiles = List.copyOf(Objects.requireNonNull(generatedFiles, "generatedFiles must not be null"));
    }

    public CBuildResult(@NotNull CCompileResult compileResult, @NotNull List<Path> generatedFiles) {
        this(
                compileResult.success(),
                compileResult.buildLog(),
                compileResult.artifacts(),
                generatedFiles
        );
    }
}
