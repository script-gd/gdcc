package dev.superice.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record CCompileResult(boolean success,
                             @NotNull String buildLog,
                             @NotNull List<Path> artifacts) {
    public CCompileResult {
        Objects.requireNonNull(buildLog, "buildLog must not be null");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
    }
}
