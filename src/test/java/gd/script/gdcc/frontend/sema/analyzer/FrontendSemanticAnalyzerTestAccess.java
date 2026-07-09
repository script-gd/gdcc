package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

/// Test-source bridge for package-private semantic analyzer compatibility hooks.
public final class FrontendSemanticAnalyzerTestAccess {
    private FrontendSemanticAnalyzerTestAccess() {
    }

    public static @NotNull FrontendAnalysisData analyzeWithLegacySharedSemanticPublication(
            @NotNull FrontendSemanticAnalyzer analyzer,
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        return analyzer.analyzeWithLegacySharedSemanticPublication(module, classRegistry, diagnosticManager);
    }
}
