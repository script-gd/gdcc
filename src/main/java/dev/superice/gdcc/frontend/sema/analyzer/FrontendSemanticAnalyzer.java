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
/// The current framework wires six stable frontend phases into one shared
/// `FrontendAnalysisData` carrier:
/// - skeleton publication
/// - lexical scope graph construction
/// - callable-parameter and supported local-variable inventory
/// - top-binding publication for symbol-category resolution
/// - chain member/call publication
/// - expression-type publication
/// - diagnostics boundary refresh after each phase
public final class FrontendSemanticAnalyzer {
    private final @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder;
    private final @NotNull FrontendScopeAnalyzer scopeAnalyzer;
    private final @NotNull FrontendVariableAnalyzer variableAnalyzer;
    private final @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer;
    private final @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer;
    private final @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer;

    public FrontendSemanticAnalyzer() {
        this(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer(),
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(@NotNull FrontendClassSkeletonBuilder classSkeletonBuilder) {
        this(
                classSkeletonBuilder,
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer(),
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer()
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
                new FrontendTopBindingAnalyzer(),
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer()
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
                new FrontendTopBindingAnalyzer(),
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                new FrontendExprTypeAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer
    ) {
        this.classSkeletonBuilder = Objects.requireNonNull(classSkeletonBuilder, "classSkeletonBuilder must not be null");
        this.scopeAnalyzer = Objects.requireNonNull(scopeAnalyzer, "scopeAnalyzer must not be null");
        this.variableAnalyzer = Objects.requireNonNull(variableAnalyzer, "variableAnalyzer must not be null");
        this.topBindingAnalyzer = Objects.requireNonNull(topBindingAnalyzer, "topBindingAnalyzer must not be null");
        this.chainBindingAnalyzer = Objects.requireNonNull(chainBindingAnalyzer, "chainBindingAnalyzer must not be null");
        this.exprTypeAnalyzer = Objects.requireNonNull(exprTypeAnalyzer, "exprTypeAnalyzer must not be null");
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

        // Top-binding analysis classifies supported use-sites into stable symbol categories while
        // still keeping member/call resolution out of scope. Keeping it separate from variable
        // analysis preserves a clean hand-off between declaration inventory and use-site binding.
        topBindingAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Chain-binding analysis consumes published symbol/scope facts and emits the first stable
        // member/call side tables without opening whole-module expression typing yet.
        chainBindingAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Expression typing consumes the published symbol/member/call facts and releases the final
        // expression-type side table without reopening the earlier chain-binding phase.
        exprTypeAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return analysisData;
    }
}
