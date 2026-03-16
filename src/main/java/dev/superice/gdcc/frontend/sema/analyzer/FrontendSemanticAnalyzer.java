package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.List;

/// Basic frontend semantic-analyzer framework.
///
/// The current framework already wires four stable frontend phases into one shared
/// `FrontendAnalysisData` carrier:
/// - skeleton publication
/// - lexical scope graph construction
/// - callable-parameter and supported local-variable binding
/// - top-binding publication skeleton for future symbol-category resolution
/// - diagnostics boundary refresh after each phase
public final class FrontendSemanticAnalyzer {
    private final @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder;
    private final @NotNull FrontendScopeAnalyzer scopeAnalyzer;
    private final @NotNull FrontendVariableAnalyzer variableAnalyzer;
    private final @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer;

    public FrontendSemanticAnalyzer() {
        this(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(@NotNull FrontendClassSkeletonBuilder classSkeletonBuilder) {
        this(
                classSkeletonBuilder,
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                new FrontendTopBindingAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer
    ) {
        this.classSkeletonBuilder = Objects.requireNonNull(classSkeletonBuilder, "classSkeletonBuilder must not be null");
        this.scopeAnalyzer = Objects.requireNonNull(scopeAnalyzer, "scopeAnalyzer must not be null");
        this.variableAnalyzer = Objects.requireNonNull(variableAnalyzer, "variableAnalyzer must not be null");
        this.topBindingAnalyzer = Objects.requireNonNull(topBindingAnalyzer, "topBindingAnalyzer must not be null");
    }

    /// Runs the current frontend analyzer framework against one module using a shared
    /// `DiagnosticManager`.
    ///
    /// `FrontendSourceUnit` no longer stores parse diagnostics. The analyzer therefore consumes
    /// parse diagnostics only through the shared manager state that callers prepared earlier in
    /// the pipeline.
    public @NotNull FrontendAnalysisData analyze(
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(units, "units must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = classSkeletonBuilder.build(
                moduleName,
                units,
                classRegistry,
                diagnosticManager,
                analysisData
        );

        // Publish the skeleton boundary before the scope phase starts so later phases can rely on
        // a stable module snapshot instead of peeking into builder internals.
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Scope analysis remains a dedicated phase after skeleton publication so later binder/body
        // work can consume one stable lexical graph instead of interleaving scope creation with
        // later semantic binding.
        scopeAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Variable analysis enriches the published lexical graph without changing how scopes are
        // constructed. Keeping it as its own phase prevents scope construction plus parameter/local
        // inventory work from drifting into one monolithic analyzer.
        variableAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Top-binding analysis currently publishes only the `symbolBindings()` phase boundary and
        // traversal skeleton. Keeping it separate from variable analysis locks the future binder
        // contract without mixing declaration inventory with use-site classification.
        topBindingAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return analysisData;
    }
}
