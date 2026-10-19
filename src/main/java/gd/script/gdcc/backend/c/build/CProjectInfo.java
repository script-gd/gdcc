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
    private final boolean stripNativeSymbols;

    /// Compatibility constructor for callers that predate backend selection: the build pins the
    /// zig backend, matching the behavior before a compiler kind existed.
    public CProjectInfo(@NotNull String projectName,
                        @NotNull GodotVersion godotVersion,
                        @NotNull Path projectPath,
                        @NotNull COptimizationLevel COptimizationLevel,
                        @NotNull TargetPlatform targetPlatform) {
        this(projectName, godotVersion, projectPath, COptimizationLevel, targetPlatform, CCompilerKind.ZIG_CC);
    }

    /// Compatibility constructor for callers that predate symbol stripping: artifacts keep zig's
    /// default DWARF output and full symbol table.
    public CProjectInfo(@NotNull String projectName,
                        @NotNull GodotVersion godotVersion,
                        @NotNull Path projectPath,
                        @NotNull COptimizationLevel COptimizationLevel,
                        @NotNull TargetPlatform targetPlatform,
                        @NotNull CCompilerKind cCompilerKind) {
        this(projectName, godotVersion, projectPath, COptimizationLevel, targetPlatform, cCompilerKind, false);
    }

    public CProjectInfo(@NotNull String projectName,
                        @NotNull GodotVersion godotVersion,
                        @NotNull Path projectPath,
                        @NotNull COptimizationLevel COptimizationLevel,
                        @NotNull TargetPlatform targetPlatform,
                        @NotNull CCompilerKind cCompilerKind,
                        boolean stripNativeSymbols) {
        super(projectName, godotVersion, projectPath);
        this.COptimizationLevel = COptimizationLevel;
        this.targetPlatform = targetPlatform;
        this.cCompilerKind = Objects.requireNonNull(cCompilerKind, "cCompilerKind must not be null");
        this.stripNativeSymbols = stripNativeSymbols;
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

    /// Whether the native build should suppress DWARF and strip the linked symbol table. Only the
    /// editor addon distribution packaging opts in; every other caller takes the `false` default.
    public boolean isStripNativeSymbols() {
        return stripNativeSymbols;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CProjectInfo that)) return false;
        if (!super.equals(o)) return false;
        return COptimizationLevel == that.COptimizationLevel
                && targetPlatform == that.targetPlatform
                && cCompilerKind == that.cCompilerKind
                && stripNativeSymbols == that.stripNativeSymbols;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), COptimizationLevel, targetPlatform, cCompilerKind, stripNativeSymbols);
    }
}
