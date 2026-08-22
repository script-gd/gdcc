package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/// Test harness for running real root-bounded owner procedures through the body SuiteResolver path.
final class FrontendSuiteResolverStageTestSupport {
    private FrontendSuiteResolverStageTestSupport() {
    }

    static void resolveAllOwners(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        resolveOwners(
                classRegistry,
                analysisData,
                diagnosticManager,
                EnumSet.allOf(FrontendSemanticStage.class)
        );
    }

    static void resolveOwners(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull Set<FrontendSemanticStage> enabledStages
    ) {
        var interfaceSurface = new FrontendInterfacePhase().analyze(classRegistry, analysisData);
        resolveOwners(interfaceSurface, classRegistry, analysisData, diagnosticManager, enabledStages);
    }

    static void resolveOwners(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull Set<FrontendSemanticStage> enabledStages
    ) {
        var checkedStages = Set.copyOf(enabledStages);
        var delegate = new FrontendBodyOwnerProcedures();
        var ownerProcedures = new FrontendStatementResolver.OwnerProcedures() {
            @Override
            public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
                if (checkedStages.contains(FrontendSemanticStage.TOP_BINDING)) {
                    delegate.runTopBinding(context, root);
                }
            }

            @Override
            public void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
                if (checkedStages.contains(FrontendSemanticStage.LOCAL_TYPE_STABILIZATION)) {
                    delegate.runLocalTypeStabilization(context, root);
                }
            }

            @Override
            public void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
                if (checkedStages.contains(FrontendSemanticStage.CHAIN_BINDING)) {
                    delegate.runChainBinding(context, root);
                }
            }

            @Override
            public void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
                if (checkedStages.contains(FrontendSemanticStage.EXPR_TYPE)) {
                    delegate.runExprType(context, root);
                }
            }

            @Override
            public void runForIterationResolution(
                    @NotNull FrontendSuiteContext context,
                    @NotNull ForStatement forStatement
            ) {
                if (checkedStages.contains(FrontendSemanticStage.FOR_ITERATION_RESOLUTION)) {
                    delegate.runForIterationResolution(context, forStatement);
                }
            }

            @Override
            public void runMatchPatternResolution(
                    @NotNull FrontendSuiteContext context,
                    @NotNull MatchStatement matchStatement
            ) {
                if (checkedStages.contains(FrontendSemanticStage.MATCH_PATTERN_RESOLUTION)) {
                    delegate.runMatchPatternResolution(context, matchStatement);
                }
            }

            @Override
            public void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
                if (checkedStages.contains(FrontendSemanticStage.VAR_TYPE_POST)) {
                    delegate.runVarTypePost(context, root);
                }
            }
        };
        new FrontendSuiteResolver(new FrontendStatementResolver(ownerProcedures)).resolve(
                interfaceSurface,
                classRegistry,
                analysisData,
                diagnosticManager
        );
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
    }
}
