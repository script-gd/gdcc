package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendWindowAnalysisContext;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Legacy comparison scheduler for the segmented semantic publication layer introduced after the
/// window-capable analyzer APIs.
///
/// This scheduler is not the planned SuiteResolver production pipeline: its `analyzeInWindow(...)`
/// calls still perform whole-module traversal, and local stabilization intentionally uses the stable
/// whole-phase path. Keep it only as a legacy comparison entry while the root-bounded body resolver
/// and per-owner patch transaction are implemented.
@Deprecated
final class FrontendSegmentedSemanticScheduler {
    private final @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer;
    private final @NotNull FrontendLocalTypeStabilizationAnalyzer localTypeStabilizationAnalyzer;
    private final @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer;
    private final @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer;
    private final @NotNull FrontendVarTypePostAnalyzer varTypePostAnalyzer;

    FrontendSegmentedSemanticScheduler(
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendLocalTypeStabilizationAnalyzer localTypeStabilizationAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendVarTypePostAnalyzer varTypePostAnalyzer
    ) {
        this.topBindingAnalyzer = Objects.requireNonNull(topBindingAnalyzer, "topBindingAnalyzer must not be null");
        this.localTypeStabilizationAnalyzer = Objects.requireNonNull(
                localTypeStabilizationAnalyzer,
                "localTypeStabilizationAnalyzer must not be null"
        );
        this.chainBindingAnalyzer = Objects.requireNonNull(chainBindingAnalyzer, "chainBindingAnalyzer must not be null");
        this.exprTypeAnalyzer = Objects.requireNonNull(exprTypeAnalyzer, "exprTypeAnalyzer must not be null");
        this.varTypePostAnalyzer = Objects.requireNonNull(varTypePostAnalyzer, "varTypePostAnalyzer must not be null");
    }

    void run(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        runTopBinding(classRegistry, analysisData, diagnosticManager);
        runLocalTypeStabilization(classRegistry, analysisData, diagnosticManager);
        runChainBinding(classRegistry, analysisData, diagnosticManager);
        runExprType(classRegistry, analysisData, diagnosticManager);
        runVarTypePost(analysisData, diagnosticManager);
    }

    private void runTopBinding(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var window = new FrontendWindowAnalysisContext(analysisData);
        topBindingAnalyzer.analyzeInWindow(classRegistry, window, diagnosticManager);
        commit(window, FrontendSemanticStage.TOP_BINDING, analysisData, diagnosticManager);
    }

    private void runLocalTypeStabilization(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        // This phase must keep its existing source-order scope writes until the scheduler grows
        // true root-bounded statement windows. A whole-module local window would delay every slot
        // update until commit and break alias chains such as `var b := a` after `var a := typed`.
        localTypeStabilizationAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
    }

    private void runChainBinding(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var window = new FrontendWindowAnalysisContext(analysisData);
        chainBindingAnalyzer.analyzeInWindow(classRegistry, window, diagnosticManager);
        commit(window, FrontendSemanticStage.CHAIN_BINDING, analysisData, diagnosticManager);
    }

    private void runExprType(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var window = new FrontendWindowAnalysisContext(analysisData);
        exprTypeAnalyzer.analyzeInWindow(classRegistry, window, diagnosticManager);
        commit(window, FrontendSemanticStage.EXPR_TYPE, analysisData, diagnosticManager);
    }

    private void runVarTypePost(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var window = new FrontendWindowAnalysisContext(analysisData);
        varTypePostAnalyzer.analyzeInWindow(window, diagnosticManager);
        commit(window, FrontendSemanticStage.VAR_TYPE_POST, analysisData, diagnosticManager);
    }

    private void commit(
            @NotNull FrontendWindowAnalysisContext window,
            @NotNull FrontendSemanticStage stage,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        analysisData.applyPatch(window.drainPatch(stage));
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
    }
}
