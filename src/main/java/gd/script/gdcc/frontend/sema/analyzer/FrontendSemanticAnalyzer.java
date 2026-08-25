package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Basic frontend semantic-analyzer framework.
///
/// This analyzer wires 12 shared frontend phases plus one compile-only gate into one shared
/// `FrontendAnalysisData` carrier:
/// - skeleton publication
/// - lexical scope graph construction
/// - callable-parameter and supported local-variable inventory
/// - interface/body suite publication for statement-local top binding, local slot stabilization,
///   chain binding, expression typing, and callable-local slot finalization
/// - annotation-usage validation
/// - diagnostics-only engine virtual override validation
/// - diagnostics-only type-check traversal
/// - diagnostics-only loop-control legality traversal
/// - compile-only final gate via `analyzeForCompile(...)`
/// - diagnostics boundary refresh after each phase
public final class FrontendSemanticAnalyzer {
    private final @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder;
    private final @NotNull FrontendScopeAnalyzer scopeAnalyzer;
    private final @NotNull FrontendVariableAnalyzer variableAnalyzer;
    private final @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer;
    private final @NotNull FrontendVirtualOverrideAnalyzer virtualOverrideAnalyzer;
    private final @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer;
    private final @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer;
    private final @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer;
    private final @NotNull FrontendInterfacePhase interfacePhase;
    private final @NotNull FrontendSuiteResolver suiteResolver;
    /// Post-suite await coroutine fixed-point pass. Instantiated internally: it is a pure function
    /// of the published analysis data and needs no test seam beyond the pipeline itself.
    private final @NotNull FrontendAwaitCoroutineAnalyzer awaitCoroutineAnalyzer =
            new FrontendAwaitCoroutineAnalyzer();

    public FrontendSemanticAnalyzer() {
        this(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
        );
    }

    public FrontendSemanticAnalyzer(
            @NotNull FrontendInterfacePhase interfacePhase,
            @NotNull FrontendSuiteResolver suiteResolver
    ) {
        this(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer(),
                interfacePhase,
                suiteResolver
        );
    }

    public FrontendSemanticAnalyzer(@NotNull FrontendClassSkeletonBuilder classSkeletonBuilder) {
        this(
                classSkeletonBuilder,
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
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
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
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
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                new FrontendLoopControlFlowAnalyzer(),
                new FrontendCompileCheckAnalyzer()
        );
    }

    /// Creates a testable active-phase pipeline while keeping body-fact publication owned solely
    /// by the default interface/body resolver pair.
    public FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendVirtualOverrideAnalyzer virtualOverrideAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer,
            @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer,
            @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer
    ) {
        this(
                classSkeletonBuilder,
                scopeAnalyzer,
                variableAnalyzer,
                annotationUsageAnalyzer,
                virtualOverrideAnalyzer,
                typeCheckAnalyzer,
                loopControlFlowAnalyzer,
                compileCheckAnalyzer,
                new FrontendInterfacePhase(),
                new FrontendSuiteResolver()
        );
    }

    private FrontendSemanticAnalyzer(
            @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder,
            @NotNull FrontendScopeAnalyzer scopeAnalyzer,
            @NotNull FrontendVariableAnalyzer variableAnalyzer,
            @NotNull FrontendAnnotationUsageAnalyzer annotationUsageAnalyzer,
            @NotNull FrontendVirtualOverrideAnalyzer virtualOverrideAnalyzer,
            @NotNull FrontendTypeCheckAnalyzer typeCheckAnalyzer,
            @NotNull FrontendLoopControlFlowAnalyzer loopControlFlowAnalyzer,
            @NotNull FrontendCompileCheckAnalyzer compileCheckAnalyzer,
            @NotNull FrontendInterfacePhase interfacePhase,
            @NotNull FrontendSuiteResolver suiteResolver
    ) {
        this.classSkeletonBuilder = Objects.requireNonNull(classSkeletonBuilder, "classSkeletonBuilder must not be null");
        this.scopeAnalyzer = Objects.requireNonNull(scopeAnalyzer, "scopeAnalyzer must not be null");
        this.variableAnalyzer = Objects.requireNonNull(variableAnalyzer, "variableAnalyzer must not be null");
        this.annotationUsageAnalyzer = Objects.requireNonNull(
                annotationUsageAnalyzer,
                "annotationUsageAnalyzer must not be null"
        );
        this.virtualOverrideAnalyzer = Objects.requireNonNull(
                virtualOverrideAnalyzer,
                "virtualOverrideAnalyzer must not be null"
        );
        this.typeCheckAnalyzer = Objects.requireNonNull(typeCheckAnalyzer, "typeCheckAnalyzer must not be null");
        this.loopControlFlowAnalyzer = Objects.requireNonNull(
                loopControlFlowAnalyzer,
                "loopControlFlowAnalyzer must not be null"
        );
        this.compileCheckAnalyzer = Objects.requireNonNull(compileCheckAnalyzer, "compileCheckAnalyzer must not be null");
        this.interfacePhase = Objects.requireNonNull(interfacePhase, "interfacePhase must not be null");
        this.suiteResolver = Objects.requireNonNull(suiteResolver, "suiteResolver must not be null");
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
        return analyzeShared(module, classRegistry, diagnosticManager);
    }

    private @NotNull FrontendAnalysisData analyzeShared(
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

        // Interface analysis freezes callable/property entry roots before the body owner
        // publication path runs. Shared facts can only enter stable storage through
        // SuiteResolver's per-owner export transaction.
        var interfaceSurface = interfacePhase.analyze(classRegistry, analysisData);
        suiteResolver.resolve(interfaceSurface, classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Await coroutine resolution runs once all callable owners resolved: signal/dynamic awaits
        // already marked their enclosing callables during EXPR_TYPE, and this pass propagates
        // transitive await-of-coroutine-call markings to a fixed point, then owns the
        // `sema.redundant_await` warnings for statically known non-coroutine callees.
        awaitCoroutineAnalyzer.analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Annotation-usage validation consumes retained annotations plus the published class/scope
        // facts, but still stays diagnostics-only and does not mutate semantic side tables.
        annotationUsageAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());

        // Engine virtual override validation consumes the published class/function metadata and
        // reports signature mismatches without skipping the owning function subtree.
        virtualOverrideAnalyzer.analyze(classRegistry, analysisData, diagnosticManager);
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
