package gd.script.gdcc.api;

import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Internal rich outcome of one analysis run: the public `AnalysisResult` plus, when the shared
/// semantic pipeline completed, the semantic payload that snapshot publication consumes.
///
/// Everything inside `Payload` belongs to one analysis generation: the AST of `module`, the side
/// tables of `analysisData`, and the GDCC classes registered in `classRegistry` were produced by
/// the same shared `FrontendSemanticAnalyzer.analyze(...)` run and must never be mixed with facts
/// from another run (including the isolated lowering-verification run, which gets its own fresh
/// registry and diagnostic manager).
record AnalysisRunResult(@NotNull AnalysisResult result, @Nullable Payload payload) {
    AnalysisRunResult {
        Objects.requireNonNull(result, "result must not be null");
        if (payload != null && result.outcome() != AnalysisResult.Outcome.COMPLETED) {
            throw new IllegalArgumentException("payload is only allowed for COMPLETED outcomes");
        }
    }

    /// Semantic payload of a completed shared analysis.
    ///
    /// @param module              the parsed AST generation
    /// @param analysisData        the shared-run semantic side tables (never the lowering run's)
    /// @param classRegistry       the shared-run registry; frozen by convention once published
    /// @param snapshotDiagnostics the shared-run diagnostics remapped to display paths, never
    /// containing compile-only `sema.compile_check` entries
    record Payload(
            @NotNull FrontendModule module,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticSnapshot snapshotDiagnostics
    ) {
        Payload {
            Objects.requireNonNull(module, "module must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            Objects.requireNonNull(snapshotDiagnostics, "snapshotDiagnostics must not be null");
        }
    }
}
