package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/// Full C backend build result after codegen files have been written and the native compiler has run.
public record CBuildResult(
        boolean success,
        @NotNull String buildLog,
        @NotNull List<Path> artifacts,
        @NotNull List<Path> generatedFiles,
        @NotNull Timing timing
) {
    public CBuildResult {
        Objects.requireNonNull(buildLog, "buildLog must not be null");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
        generatedFiles = List.copyOf(Objects.requireNonNull(generatedFiles, "generatedFiles must not be null"));
        Objects.requireNonNull(timing, "timing must not be null");
    }

    public CBuildResult(
            boolean success,
            @NotNull String buildLog,
            @NotNull List<Path> artifacts,
            @NotNull List<Path> generatedFiles
    ) {
        this(success, buildLog, artifacts, generatedFiles, Timing.zero());
    }

    public CBuildResult(@NotNull CCompileResult compileResult, @NotNull List<Path> generatedFiles) {
        this(compileResult, generatedFiles, Timing.zero());
    }

    public CBuildResult(
            @NotNull CCompileResult compileResult,
            @NotNull List<Path> generatedFiles,
            @NotNull Timing timing
    ) {
        this(
                compileResult.success(),
                compileResult.buildLog(),
                compileResult.artifacts(),
                generatedFiles,
                timing
        );
    }

    public record Timing(
            @NotNull Duration includeExtraction,
            @NotNull Duration codeGeneration,
            @NotNull Duration generatedFileWrite,
            @NotNull Duration compileInputCollection,
            @NotNull Duration nativeCompile,
            @NotNull Duration total
    ) {
        public Timing {
            Objects.requireNonNull(includeExtraction, "includeExtraction must not be null");
            Objects.requireNonNull(codeGeneration, "codeGeneration must not be null");
            Objects.requireNonNull(generatedFileWrite, "generatedFileWrite must not be null");
            Objects.requireNonNull(compileInputCollection, "compileInputCollection must not be null");
            Objects.requireNonNull(nativeCompile, "nativeCompile must not be null");
            Objects.requireNonNull(total, "total must not be null");
        }

        public static @NotNull Timing zero() {
            return new Timing(
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ZERO
            );
        }
    }
}
