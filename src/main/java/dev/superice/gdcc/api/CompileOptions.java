package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Per-module compile settings owned by the API layer.
///
/// The defaults intentionally match the current backend surface without forcing step-1 callers to
/// choose a project directory before compile orchestration exists.
public record CompileOptions(
        @NotNull GodotVersion godotVersion,
        @Nullable Path projectPath,
        @NotNull COptimizationLevel optimizationLevel,
        @NotNull TargetPlatform targetPlatform,
        boolean strictMode,
        @NotNull String outputMountRoot
) {
    public static final String DEFAULT_OUTPUT_MOUNT_ROOT = "/__build__";

    public CompileOptions {
        Objects.requireNonNull(godotVersion, "godotVersion must not be null");
        Objects.requireNonNull(optimizationLevel, "optimizationLevel must not be null");
        Objects.requireNonNull(targetPlatform, "targetPlatform must not be null");
        outputMountRoot = StringUtil.requireTrimmedNonBlank(outputMountRoot, "outputMountRoot");
    }

    public static @NotNull CompileOptions defaults() {
        return new CompileOptions(
                GodotVersion.V451,
                null,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform(),
                false,
                DEFAULT_OUTPUT_MOUNT_ROOT
        );
    }
}
