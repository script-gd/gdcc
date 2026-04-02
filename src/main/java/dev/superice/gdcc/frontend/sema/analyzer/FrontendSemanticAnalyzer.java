package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Basic frontend semantic-analyzer framework.
///
/// The current framework wires ten shared frontend phases plus one compile-only gate into one shared
/// `FrontendAnalysisData` carrier:
/// - skeleton publication
/// - lexical scope graph construction
/// - callable-parameter and supported local-variable inventory
/// - top-binding publication for symbol-category resolution
/// - chain member/call publication
/// - expression-type publication
/// - callable-local slot-type publication
/// - annotation-usage validation
/// - diagnostics-only type-check traversal
/// - diagnostics-only loop-control legality traversal
/// - compile-only final gate via `analyzeForCompile(...)`
/// - diagnostics boundary refresh after each phase
public final class FrontendSemanticAnalyzer {
    private final @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder;
    private final @NotNull FrontendScopeAnalyzer scopeAnalyzer;
    private final @NotNull FrontendVariableAnalyzer variableAnalyzer;
    private final @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer;
    private final @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer;
    private final @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer;
    private final @NotNull FrontendVarTypePostAnalyzer varTypePostAnalyzer;
    private final @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer;
    private final @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer;
    private final @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer;
    private final @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer;

    public FrontendSemanticAnalyzer() {
        this(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer(),
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer(),
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(@NotNull FrontendClassSkeletonBuilder classSkeletonBuilder) {
        this(
                classSkeletonBuilder,
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendTopBindingAnalyzer(),
                new FrontendChainBindingAnalyzer(),
                new FrontendExprTypeAnalyzer(),
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
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
                new FrontendExprTypeAnalyzer(),
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
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
                new FrontendExprTypeAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer()
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
                new FrontendExprTypeAnalyzer(),
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
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
                new FrontendExprTypeAnalyzer(),
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
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
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                exprTypeAnalyzer,
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                exprTypeAnalyzer,
                new FrontendVarTypePostAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                typeCheckAnalyzer,
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                exprTypeAnalyzer,
                new FrontendVarTypePostAnalyzer(),
                annotationUsageAnalyzer,
                typeCheckAnalyzer,
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer,
            @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                exprTypeAnalyzer,
                new FrontendVarTypePostAnalyzer(),
                annotationUsageAnalyzer,
                typeCheckAnalyzer,
                loopControlFlowAnalyzer,
                new FrontendCompileCheckAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer,
            @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                exprTypeAnalyzer,
                new FrontendVarTypePostAnalyzer(),
                annotationUsageAnalyzer,
                typeCheckAnalyzer,
                new FrontendLoopControlFlowAnalyzer(),
                compileCheckAnalyzer
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer,
            @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer,
            @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                topBindingAnalyzer,
                chainBindingAnalyzer,
                exprTypeAnalyzer,
                new FrontendVarTypePostAnalyzer(),
                annotationUsageAnalyzer,
                typeCheckAnalyzer,
                loopControlFlowAnalyzer,
                compileCheckAnalyzer
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendTopBindingAnalyzer topBindingAnalyzer,
            @NotNull FrontendChainBindingAnalyzer chainBindingAnalyzer,
            @NotNull FrontendExprTypeAnalyzer exprTypeAnalyzer,
            @NotNull FrontendVarTypePostAnalyzer varTypePostAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer,
            @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer,
            @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer
    ) {
        this.classSkeletonBuilder = Objects.requireNonNull(classSkeletonBuilder, "classSkeletonBuilder must not be null");
        this.scopeAnalyzer = Objects.requireNonNull(scopeAnalyzer, "scopeAnalyzer must not be null");
        this.variableAnalyzer = Objects.requireNonNull(variableAnalyzer, "variableAnalyzer must not be null");
        this.topBindingAnalyzer = Objects.requireNonNull(topBindingAnalyzer, "topBindingAnalyzer must not be null");
        this.chainBindingAnalyzer = Objects.requireNonNull(chainBindingAnalyzer, "chainBindingAnalyzer must not be null");
        this.exprTypeAnalyzer = Objects.requireNonNull(exprTypeAnalyzer, "exprTypeAnalyzer must not be null");
        this.varTypePostAnalyzer = Objects.requireNonNull(
                varTypePostAnalyzer,
                "varTypePostAnalyzer must not be null"
        );
        this.annotationUsageAnalyzer = Objects.requireNonNull(
                annotationUsageAnalyzer,
                "annotationUsageAnalyzer must not be null"
        );
        this.typeCheckAnalyzer = Objects.requireNonNull(typeCheckAnalyzer, "typeCheckAnalyzer must not be null");
        this.loopControlFlowAnalyzer = Objects.requireNonNull(
                loopControlFlowAnalyzer,
                "loopControlFlowAnalyzer must not be null"
        );
        this.compileCheckAnalyzer = Objects.requireNonNull(compileCheckAnalyzer, "compileCheckAnalyzer must not be null");
    }

    /// Runs the current frontend analyzer framework against one module using a shared
    /// `DiagnosticManager`.
    ///
    /// `FrontendSourceUnit` no longer stores parse diagnostics. The analyzer therefore consumes
    /// parse diagnostics only through the shared manager state that callers prepared earlier in
    /// the pipeline.
    ///
    /// This shared semantic entrypoint intentionally does not guarantee lowering readiness.
    /// Compile callers must use `analyzeForCompile(...)` and check the resulting diagnostics for
    /// errors before allowing frontend output to enter lowering.
    public @NotNull FrontendAnalysisData analyze(
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = classSkeletonBuilder.build(
                module,
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

        // Lowering is only allowed to consume published facts, so the final callable-local slot
        // types are republished here after expression typing has already settled `:=` backfill.
        varTypePostAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Annotation-usage validation consumes retained annotations plus the published class/scope
        // facts, but still stays diagnostics-only and does not mutate semantic side tables.
        annotationUsageAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Type checking is diagnostics-only for now: it consumes the published frontend facts but
        // must not introduce new side tables or rewrite earlier publication boundaries.
        typeCheckAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Loop-control legality is also diagnostics-only, but it must run on the shared semantic
        // path so invalid `break` / `continue` never rely on lowering fail-fast to become visible.
        loopControlFlowAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return analysisData;
    }

    /// Runs the shared semantic pipeline plus the compile-only final gate.
    ///
    /// This split keeps the default semantic entrypoint reusable for inspection/LSP-style tooling
    /// while still giving lowering callers one dedicated compile-only contract. Future
    /// frontend-to-LIR lowering must treat this entrypoint plus `diagnostics().hasErrors() == false`
    /// as the minimum precondition before compilation can continue.
    public @NotNull FrontendAnalysisData analyzeForCompile(
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var analysisData = analyze(module, classRegistry, diagnosticManager);
        compileCheckAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return analysisData;
    }
}
