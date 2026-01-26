package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

public class CProjectInfo extends ProjectInfo {
    private final @NotNull COptimizationLevel COptimizationLevel;
    private final @NotNull TargetPlatform targetPlatform;

    public CProjectInfo(@NotNull String projectName,
                        @NotNull GodotVersion godotVersion,
                        @NotNull Path projectPath,
                        @NotNull COptimizationLevel COptimizationLevel,
                        @NotNull TargetPlatform targetPlatform) {
        super(projectName, godotVersion, projectPath);
        this.COptimizationLevel = COptimizationLevel;
        this.targetPlatform = targetPlatform;
    }

    public @NotNull COptimizationLevel getOptimizationLevel() {
        return COptimizationLevel;
    }

    public @NotNull TargetPlatform getTargetPlatform() {
        return targetPlatform;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CProjectInfo that)) return false;
        if (!super.equals(o)) return false;
        return COptimizationLevel == that.COptimizationLevel && targetPlatform == that.targetPlatform;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), COptimizationLevel, targetPlatform);
    }
}
