package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Variable-analysis phase entry point.
///
/// This phase sits immediately after lexical scope construction. Phase 2 intentionally keeps the
/// implementation narrow:
/// - require the skeleton and diagnostics boundary published by earlier phases
/// - require the scope phase to have published one top-level `ClassScope` per accepted source file
/// - reserve the shared `DiagnosticManager` entry point for later parameter/local binding work
///
/// Concrete parameter/local inventory enrichment is still deferred to the follow-up phases in
/// `frontend_variable_analyzer_plan.md`.
public class FrontendVariableAnalyzer {
    /// Runs variable analysis against the shared analysis carrier.
    ///
    /// The current implementation only validates that the earlier frontend phases already
    /// published the boundaries this phase depends on. Later tasks will extend this method with
    /// parameter/local prefill and diagnostics without changing the public entry point.
    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        // Variable analysis enriches the already-published lexical graph in place. Requiring one
        // top-level scope per accepted source file keeps the phase boundary explicit and makes
        // missing scope publication fail fast instead of silently skipping later binding work.
        var scopesByAst = analysisData.scopesByAst();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }
    }
}
