package gd.script.gdcc.api;

import org.jetbrains.annotations.NotNull;

/// Per-request settings for `API.analyze(...)`.
///
/// @param includeLowering When `true`, the analysis pass runs frontend lowering after parsing —
/// instead of stopping at the shared semantic entrypoint — so callers can verify whether the
///                        module can currently lower to LIR. Lowering reruns semantic analysis
///                        through the compile-only gate. The C backend never runs: no C code is
///                        generated and no native build starts, regardless of this flag.
public record AnalyzeOptions(boolean includeLowering) {
    /// Default analysis runs the shared semantic pipeline only, which matches the editor
    /// warning/error flow without paying for lowering verification.
    public static @NotNull AnalyzeOptions defaults() {
        return new AnalyzeOptions(false);
    }
}
