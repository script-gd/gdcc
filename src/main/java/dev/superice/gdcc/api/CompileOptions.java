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
/// The defaults intentionally match the current backend surface while allowing callers to configure
/// a module before assigning a concrete local build directory.
///
/// @param godotVersion Selects the Godot API/ABI surface the compile pipeline should target. The
///                     compile pipeline uses it to load matching extension metadata and keep
///                     generated code aligned with that engine version's contracts.
/// @param projectPath Local host-filesystem build directory used to materialize generated C sources
///                    and final native artifacts. This is intentionally distinct from module VFS
///                    paths like `/src/main.gd`; `null` means the module has not been assigned a
///                    real build directory yet.
/// @param optimizationLevel Declares whether the backend build should favor debug-oriented or
///                          release-oriented output. The API layer stores this as an immutable
///                          module snapshot so later build orchestration can map it to compiler and
///                          linker flags without inventing a second config container.
/// @param targetPlatform Declares the output platform rather than the current host platform. It
///                       defaults to the native host, but callers can set cross-targets such as
///                       `WEB_WASM32`; backend build code uses it for target triples and output
///                       naming.
/// @param strictMode Module-level compile policy switch reserved for stricter behavior. It already
///                   round-trips through the API and is passed into codegen context so validation
///                   can tighten diagnostics or guard rails without reshaping module state.
/// @param outputMountRoot Absolute module-VFS directory under which successful compile outputs will
///                        be linked back into the virtual filesystem. This must stay a normalized
///                        virtual path such as `/__build__` or `/build/demo`, not a host filesystem
///                        path.
public record CompileOptions(
        @NotNull GodotVersion godotVersion,
        @Nullable Path projectPath,
        @NotNull COptimizationLevel optimizationLevel,
        @NotNull TargetPlatform targetPlatform,
        boolean strictMode,
        @NotNull String outputMountRoot
) {
    /// Default VFS mount root reserved for compiler-generated files and final artifacts.
    public static final String DEFAULT_OUTPUT_MOUNT_ROOT = "/__build__";

    public CompileOptions {
        Objects.requireNonNull(godotVersion, "godotVersion must not be null");
        projectPath = validateProjectPath(projectPath);
        Objects.requireNonNull(optimizationLevel, "optimizationLevel must not be null");
        Objects.requireNonNull(targetPlatform, "targetPlatform must not be null");
        outputMountRoot = normalizeOutputMountRoot(outputMountRoot);
    }

    /// Returns the bootstrap profile used for new modules before callers provide an explicit build
    /// directory or compilation policy.
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

    private static @Nullable Path validateProjectPath(@Nullable Path projectPath) {
        if (projectPath == null) {
            return null;
        }
        StringUtil.requireTrimmedNonBlank(projectPath.toString(), "projectPath");
        return projectPath;
    }

    /// The mount root is part of the module VFS contract, so it reuses the same normalized path
    /// semantics as regular API entry paths instead of accepting host-specific separators.
    private static @NotNull String normalizeOutputMountRoot(@NotNull String outputMountRoot) {
        var normalizedText = StringUtil.requireTrimmedNonBlank(outputMountRoot, "outputMountRoot");
        try {
            return VirtualPath.parse(normalizedText).text();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("outputMountRoot " + exception.getMessage(), exception);
        }
    }
}
