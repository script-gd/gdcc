package gd.script.gdcc.api;

import org.jetbrains.annotations.NotNull;

/// Package-private seam around the off-latch analysis execution phase. Tests use it to
/// deterministically block or reorder analysis runs without `sleep`-based flakiness.
@FunctionalInterface
interface AnalysisRunSeam {
    @NotNull AnalysisRunResult run(
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions
    );
}
