package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

public class CProjectInfo extends ProjectInfo {
    private final @NotNull COptimizationLevel COptimizationLevel;
    private final @NotNull TargetPlatform targetPlatform;
    private final @NotNull CCompilerKind cCompilerKind;

    /// Compatibility constructor for callers that predate backend selection: the build pins the
    /// zig backend, matching the behavior before a compiler kind existed.
    public CProjectInfo(@NotNull String projectName,
                        @NotNull GodotVersion godotVersion,
                        @NotNull Path projectPath,
                        @NotNull COptimizationLevel COptimizationLevel,
                        @NotNull TargetPlatform targetPlatform) {
        this(projectName, godotVersion, projectPath, COptimizationLevel, targetPlatform, CCompilerKind.ZIG_CC);
    }

    public CProjectInfo(@NotNull String projectName,
                        @NotNull GodotVersion godotVersion,
                        @NotNull Path projectPath,
                        @NotNull COptimizationLevel COptimizationLevel,
                        @NotNull TargetPlatform targetPlatform,
                        @NotNull CCompilerKind cCompilerKind) {
        super(projectName, godotVersion, projectPath);
        this.COptimizationLevel = COptimizationLevel;
        this.targetPlatform = targetPlatform;
        this.cCompilerKind = Objects.requireNonNull(cCompilerKind, "cCompilerKind must not be null");
    }

    public @NotNull COptimizationLevel getOptimizationLevel() {
        return COptimizationLevel;
    }

    public @NotNull TargetPlatform getTargetPlatform() {
        return targetPlatform;
    }

    public @NotNull CCompilerKind getCCompilerKind() {
        return cCompilerKind;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CProjectInfo that)) return false;
        if (!super.equals(o)) return false;
        return COptimizationLevel == that.COptimizationLevel
                && targetPlatform == that.targetPlatform
                && cCompilerKind == that.cCompilerKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), COptimizationLevel, targetPlatform, cCompilerKind);
    }
}
