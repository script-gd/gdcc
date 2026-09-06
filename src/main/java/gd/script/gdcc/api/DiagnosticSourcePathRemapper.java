package gd.script.gdcc.api;

import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Shared API-layer remapping from compiler-facing logical source paths to caller-facing display
/// paths.
///
/// Frontend internals still track host-usable logical paths, but API callers care about the
/// original caller-facing labels such as `res://player.gd`. Compile and analysis results therefore
/// remap matching frontend diagnostic source paths back to each frozen source snapshot's
/// `displayPath`. The type is public only because the compile task runner lives in a sub-package;
/// it is not part of the RPC-facing facade surface.
public final class DiagnosticSourcePathRemapper {
    private DiagnosticSourcePathRemapper() {
    }

    /// The remapping key for one logical path matches the normalized text form frontend
    /// diagnostics carry, so callers can key their display-path lookup with it directly.
    public static @NotNull String logicalPathKey(@NotNull Path logicalPath) {
        return FrontendDiagnostic.sourcePathText(logicalPath);
    }

    public static @NotNull DiagnosticSnapshot remap(
            @NotNull Map<String, String> displayPathsByLogicalPath,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        Objects.requireNonNull(displayPathsByLogicalPath, "displayPathsByLogicalPath must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (diagnostics.isEmpty()) {
            return diagnostics;
        }
        var remappedDiagnostics = diagnostics.asList().stream()
                .map(diagnostic -> remapDiagnostic(diagnostic, displayPathsByLogicalPath))
                .toList();
        return new DiagnosticSnapshot(remappedDiagnostics);
    }

    private static @NotNull FrontendDiagnostic remapDiagnostic(
            @NotNull FrontendDiagnostic diagnostic,
            @NotNull Map<String, String> displayPathsByLogicalPath
    ) {
        var sourcePath = diagnostic.sourcePath();
        if (sourcePath == null) {
            return diagnostic;
        }
        var displayPath = displayPathsByLogicalPath.get(sourcePath);
        if (displayPath == null || Objects.equals(sourcePath, displayPath)) {
            return diagnostic;
        }
        return new FrontendDiagnostic(
                diagnostic.severity(),
                diagnostic.category(),
                diagnostic.message(),
                displayPath,
                diagnostic.range()
        );
    }
}
