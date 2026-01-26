package dev.superice.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

public record CBuildResult(boolean success,
                           @NotNull String buildLog,
                           @NotNull List<Path> artifacts) {
}
