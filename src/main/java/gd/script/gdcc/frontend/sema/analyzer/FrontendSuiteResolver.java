package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBodyStructuralCompleteness;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendLambdaCapturePlan;
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.LambdaCaptureEntry;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendCallableReturnTypeSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.patch.FrontendCallableExportBatch;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Body-suite coordinator for the staged semantic pipeline.
///
/// The resolver uses statement-local owner procedures. Tests may inject a custom
/// `FrontendStatementResolver` to record traversal shape, but production body facts must flow through
/// the typed lexical environment and ordered patch transaction.
public class FrontendSuiteResolver {
    private static final @NotNull String UNSUPPORTED_BINDING_SUBTREE_CATEGORY =
            "sema.unsupported_binding_subtree";
    private static final @NotNull String UNSUPPORTED_CHAIN_ROUTE_CATEGORY = "sema.unsupported_chain_route";
    /// Capture kind mirrored for the synthetic `self` capture (parameter-shaped).
    private static final @NotNull String SELF_CAPTURE_NAME = "self";

    private final @NotNull FrontendStatementResolver statementResolver;
    private final @NotNull FrontendParameterDefaultMetadataOwner parameterDefaultMetadataOwner;
    /// Per-owning-class counters backing `_lambda_<k>` synthetic names; resolution order follows
    /// source appearance order, so names stay stable across runs of the same module.
    private final @NotNull Map<String, Integer> lambdaNameCountersByOwningClass = new HashMap<>();
    /// Lazily built reverse view of `FrontendAnalysisData.scopesByAst()` (Scope → declaration
    /// node), consumed only by `enclosingNonLambdaCallable(...)` when a lambda needs its nearest
    /// non-lambda enclosing callable AST for the plan's `enclosingCallable` identity and the
    /// inherited static/instance restriction.
    ///
    /// Construction rules (see `scopeToAstIndex`):
    /// - one-shot build on first lambda resolution, keyed by scope identity (`IdentityHashMap`);
    /// - only callable declaration nodes (`FunctionDeclaration` / `ConstructorDeclaration` /
    ///   `LambdaExpression`) may occupy a `CallableScope` slot, because parameters and body nodes
    ///   alias the same scope in the forward table;
    /// - first-wins (`putIfAbsent`) among the surviving candidates.
    ///
    /// Boundaries:
    /// - valid only because the forward table is frozen before this resolver runs:
    ///   `FrontendScopeAnalyzer` is its sole writer, and the suite phase never adds node→scope
    ///   entries (`resetCaptureType` rewrites scope-internal `ScopeValue`s, not the table);
    /// - the cache lives and dies with this per-run resolver instance — no cross-run staleness;
    /// - if a future phase ever publishes new node→scope entries mid-suite, this index must be
    ///   invalidated or rebuilt instead of trusted.
    private @Nullable Map<Scope, Node> cachedScopeToAstIndex;

    public FrontendSuiteResolver() {
        this(new FrontendStatementResolver(new FrontendBodyOwnerProcedures()));
    }

    public FrontendSuiteResolver(@NotNull FrontendStatementResolver statementResolver) {
        this.statementResolver = Objects.requireNonNull(statementResolver, "statementResolver must not be null");
        this.parameterDefaultMetadataOwner = new FrontendParameterDefaultMetadataOwner(statementResolver);
    }

    public void resolve(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(interfaceSurface, "interfaceSurface must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        // Parameter-default sweep runs before any callable body so body call sites only observe
        // finalized `defaultValueFunc` metadata (accepted) or required parameters (reclaimed).
        parameterDefaultMetadataOwner.sweep(interfaceSurface, classRegistry, analysisData, diagnosticManager);

        for (var callableOwner : interfaceSurface.suiteEntryRoots().callableOwners()) {
            // Recorded lambdas are resolved through the nested trigger while their enclosing
            // statement is processed; the top-level loop must not resolve them a second time.
            // Lambdas left unresolved (e.g. tests with custom owner procedures that never trigger
            // the nested path) still resolve here with no enclosing overlay for capture filling.
            if (callableOwner instanceof LambdaExpression && analysisData.lambdaPlans().containsKey(callableOwner)) {
                continue;
            }
            resolveCallableOwner(interfaceSurface, callableOwner, classRegistry, analysisData, diagnosticManager, null);
        }
        for (var propertyInitializer : interfaceSurface.suiteEntryRoots().propertyInitializers()) {
            resolvePropertyInitializer(interfaceSurface, propertyInitializer, classRegistry, analysisData, diagnosticManager);
        }
    }

    /// Resolves one root or nested suite and defers its stable publication to the callable export batch.
    ///
    /// Child environments retain separate pending and committed overlays. Their transactions join the
    /// root callable's batch rather than becoming visible through stable side tables mid-resolution.
    public void resolveSuite(@NotNull FrontendSuiteContext context, @NotNull Block block) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var blockScope = requireBlockScope(context, block);
        FrontendBodyStructuralCompleteness.requireStructurallyCompleteBody(
                context.analysisData(),
                context.interfaceSurface(),
                block,
                blockScope
        );
        for (var statement : block.statements()) {
            statementResolver.resolveStatement(context, statement, this::resolveChildSuite);
        }
        var transaction = context.typedEnvironment().exportPatchTransaction();
        var exportBatch = context.exportBatch();
        if (exportBatch != null) {
            exportBatch.accumulate(transaction);
        } else {
            transaction.applyTo(context.analysisData());
        }
        context.analysisData().updateDiagnostics(context.diagnosticManager().snapshot());
    }

    private void resolveCallableOwner(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Node callableOwner,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @Nullable FrontendTypedLexicalEnvironment outerEnvironment
    ) {
        var body = callableBody(callableOwner);
        if (body == null) {
            throw new IllegalStateException("Suite entry callable owner has no executable body");
        }
        var bodyScope = analysisData.scopesByAst().get(body);
        if (!(bodyScope instanceof BlockScope blockScope)) {
            throw new IllegalStateException("Suite entry callable body has no published BlockScope");
        }
        // A lambda inherits its restriction / static context from the nearest enclosing non-lambda
        // callable; the same node is recorded as `enclosingCallable` in the plan.
        var restrictionOwner = callableOwner instanceof LambdaExpression lambdaExpression
                ? enclosingNonLambdaCallable(lambdaExpression, analysisData)
                : callableOwner;
        var environment = new FrontendTypedLexicalEnvironment(
                blockScope,
                analysisData,
                null,
                interfaceSurface.typedLexicalBaseline()
        );
        var exportBatch = new FrontendCallableExportBatch();
        var currentCallableReturnType = FrontendCallableReturnTypeSupport.resolveReturnTypeOrNull(
                callableOwner,
                blockScope.owningClassOrNull()
        );
        var context = new FrontendSuiteContext(
                sourcePathFor(interfaceSurface, callableOwner, analysisData),
                callableOwner,
                body,
                blockScope,
                blockScope,
                restrictionForCallable(restrictionOwner),
                isStaticCallable(restrictionOwner),
                null,
                interfaceSurface,
                environment,
                analysisData,
                diagnosticManager,
                classRegistry,
                exportBatch,
                currentCallableReturnType,
                this::resolveNestedLambdaOwner,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        );
        if (callableOwner instanceof LambdaExpression lambdaExpression) {
            fillAndPublishLambdaPlan(context, lambdaExpression, restrictionOwner, outerEnvironment);
        }
        runCallableEntryVarTypePost(context, callableOwner);
        resolveSuite(context, body);
        // Stable export is ordered but non-atomic: queued transactions are not preflighted together,
        // and a later failure does not roll back patches or transactions that were already applied.
        exportBatch.applyTo(analysisData);
    }

    /// Nested resolve trigger: the only production entry point for recorded lambdas.
    ///
    /// The lambda resolves with its own independent export batch applied immediately at completion,
    /// so its plan and body facts become stable before the enclosing statement's expr-type owner
    /// could publish a callable type for the lambda node. The outer typed environment is threaded
    /// through only for declaration-anchored capture type filling; the lambda's own environment
    /// stays parentless so body resolution can never read outer locals past the capture boundary.
    private void resolveNestedLambdaOwner(
            @NotNull FrontendSuiteContext outerContext,
            @NotNull LambdaExpression lambda
    ) {
        resolveCallableOwner(
                outerContext.interfaceSurface(),
                lambda,
                outerContext.classRegistry(),
                outerContext.analysisData(),
                outerContext.diagnosticManager(),
                outerContext.typedEnvironment()
        );
    }

    /// Fills declaration-site capture types and publishes the first complete `FrontendLambdaPlan`.
    ///
    /// Runs at nested resolve entry, before any lambda body statement is processed:
    /// each capture type is resolved from its source binding — parameters keep their declared type,
    /// outer captures keep their already frozen type, locals go through the enclosing typed
    /// environment's declaration-anchored overlay (falling back to the inventory baseline), and a
    /// leading `self` capture takes the enclosing class instance type. Every fill is mirrored to the
    /// `CallableScope` capture binding via `resetCaptureType` so body type-check, which reads
    /// captures without an overlay, stays same-source with the published plan.
    private void fillAndPublishLambdaPlan(
            @NotNull FrontendSuiteContext context,
            @NotNull LambdaExpression lambda,
            @NotNull Node enclosingCallable,
            @Nullable FrontendTypedLexicalEnvironment outerEnvironment
    ) {
        var analysisData = context.analysisData();
        if (!(analysisData.scopesByAst().get(lambda) instanceof CallableScope lambdaScope)) {
            throw new IllegalStateException("Recorded lambda has no published CallableScope");
        }
        var owningClass = lambdaScope.owningClassOrNull();
        if (owningClass == null) {
            throw new IllegalStateException("Recorded lambda has no owning class");
        }
        var captures = new ArrayList<LambdaCaptureEntry>();
        for (var capture : lambdaScope.captures()) {
            var declaration = Objects.requireNonNull(
                    capture.declaration(),
                    "capture '" + capture.name() + "' has no source declaration"
            );
            var entry = fillCaptureType(lambdaScope, capture, declaration, owningClass.getName(), outerEnvironment);
            lambdaScope.resetCaptureType(entry.name(), declaration, entry.type());
            captures.add(entry);
        }
        // The declared return type resolves exactly once here, at nested-resolve entry: type-check
        // (return slot) and lowering (shell return type) both consume the published plan value, so
        // an unknown annotation warns once and the two consumers can never drift.
        var returnType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                lambda.returnType(),
                lambdaScope,
                analysisData.moduleSkeleton().topLevelCanonicalNameMap(),
                context.sourcePath(),
                context.diagnosticManager()
        );
        var plan = new FrontendLambdaPlan(
                lambda,
                nextLambdaSyntheticName(owningClass.getName()),
                FrontendLambdaCapturePlan.of(captures),
                returnType,
                enclosingCallable,
                owningClass.getName()
        );
        context.typedEnvironment().putLambdaPlan(FrontendSemanticStage.LAMBDA_RESOLUTION, lambda, plan);
    }

    private @NotNull LambdaCaptureEntry fillCaptureType(
            @NotNull CallableScope lambdaScope,
            @NotNull ScopeValue capture,
            @NotNull Object declaration,
            @NotNull String owningClassName,
            @Nullable FrontendTypedLexicalEnvironment outerEnvironment
    ) {
        if (capture.name().equals(SELF_CAPTURE_NAME)) {
            return new LambdaCaptureEntry(
                    capture.name(),
                    new GdObjectType(owningClassName),
                    ScopeValueKind.PARAMETER,
                    declaration
            );
        }
        var source = requireCaptureSourceBinding(lambdaScope, capture, declaration);
        var sourceType = switch (source.value().kind()) {
            case PARAMETER, CAPTURE -> source.value().type();
            case LOCAL -> declarationSiteLocalType(source, capture, outerEnvironment);
            default -> throw new IllegalStateException(
                    "capture '" + capture.name() + "' source kind must be PARAMETER/LOCAL/CAPTURE"
            );
        };
        return new LambdaCaptureEntry(capture.name(), sourceType, source.value().kind(), declaration);
    }

    /// Reads the declaration-site type of a captured local: the enclosing environment's flushed
    /// stabilization / for-iteration update for that exact declaration, or the inventory baseline
    /// (explicit declared type or `Variant`) when no update exists. Never reads `VAR_TYPE_POST` or
    /// post-declaration refinements.
    private static @NotNull GdType declarationSiteLocalType(
            @NotNull CaptureSourceBinding source,
            @NotNull ScopeValue capture,
            @Nullable FrontendTypedLexicalEnvironment outerEnvironment
    ) {
        if (outerEnvironment != null && source.scope() instanceof BlockScope blockScope) {
            var overlayType = outerEnvironment.declarationSiteLocalSlotType(
                    blockScope,
                    capture.name(),
                    Objects.requireNonNull(capture.declaration(), "capture declaration must not be null")
            );
            if (overlayType != null) {
                return overlayType;
            }
        }
        return source.value().type();
    }

    /// Re-locates the binding a capture was planned from, walking innermost-first from the lambda
    /// boundary. The hit must carry the same declaration identity the inventory phase recorded;
    /// anything else means the scope graph drifted between phases and the compiler must fail fast.
    private static @NotNull CaptureSourceBinding requireCaptureSourceBinding(
            @NotNull CallableScope lambdaScope,
            @NotNull ScopeValue capture,
            @NotNull Object declaration
    ) {
        var current = lambdaScope.getParentScope();
        while (current != null) {
            var hit = current.resolveValueHere(capture.name());
            if (hit != null) {
                if (hit.declaration() != declaration) {
                    throw new IllegalStateException(
                            "capture '" + capture.name() + "' source binding declaration drifted"
                    );
                }
                return new CaptureSourceBinding(current, hit);
            }
            current = current.getParentScope();
        }
        throw new IllegalStateException("capture '" + capture.name() + "' has no source binding");
    }

    private record CaptureSourceBinding(@NotNull Scope scope, @NotNull ScopeValue value) {
    }

    /// Finds the nearest enclosing non-lambda callable AST through the scope graph: the lambda's
    /// callable scope parent chain leads to the enclosing `FUNCTION_DECLARATION` /
    /// `CONSTRUCTOR_DECLARATION` scope, which maps back to its declaration node.
    private @NotNull Node enclosingNonLambdaCallable(
            @NotNull LambdaExpression lambda,
            @NotNull FrontendAnalysisData analysisData
    ) {
        if (!(analysisData.scopesByAst().get(lambda) instanceof CallableScope lambdaScope)) {
            throw new IllegalStateException("Recorded lambda has no published CallableScope");
        }
        var current = lambdaScope.getParentScope();
        while (current != null) {
            if (current instanceof CallableScope callableScope
                    && callableScope.kind() != CallableScopeKind.LAMBDA_EXPRESSION) {
                var node = scopeToAstIndex(analysisData).get(callableScope);
                if (node == null) {
                    throw new IllegalStateException("Enclosing callable scope has no AST declaration");
                }
                return node;
            }
            current = current.getParentScope();
        }
        throw new IllegalStateException("Lambda has no enclosing non-lambda callable");
    }

    /// Builds the scope → declaration-node reverse index once per run.
    ///
    /// The forward table is deliberately many-to-one: `visitCallableBoundary` records the callable
    /// declaration itself and then every `Parameter` against the SAME `CallableScope`, so a naive
    /// reverse `put` would let the last parameter silently shadow the declaration node (the
    /// "parameter shadowing" bug). The reverse query is a semantic choice — "which node declares
    /// this scope" — so construction encodes that policy in two steps:
    ///
    /// 1. Type filter: only callable declaration nodes are eligible candidates; parameters and
    ///    other aliasing nodes never enter the index at all.
    /// 2. First-wins: `scopesByAst` iterates in scope-analyzer publication order, and
    ///    `visitCallableBoundary` always calls `recordScope(callableOwner, ...)` BEFORE walking
    ///    the parameter list, so among the surviving candidates the first write for a scope IS
    ///    its declaration node. `putIfAbsent` therefore deterministically keeps the declaration
    ///    even if a future shape ever produced duplicate declaration candidates for one scope.
    private @NotNull Map<Scope, Node> scopeToAstIndex(@NotNull FrontendAnalysisData analysisData) {
        var index = cachedScopeToAstIndex;
        if (index == null) {
            index = new IdentityHashMap<>();
            for (var entry : analysisData.scopesByAst().entrySet()) {
                if (entry.getKey() instanceof FunctionDeclaration
                        || entry.getKey() instanceof ConstructorDeclaration
                        || entry.getKey() instanceof LambdaExpression) {
                    index.putIfAbsent(entry.getValue(), entry.getKey());
                }
            }
            cachedScopeToAstIndex = index;
        }
        return index;
    }

    private @NotNull String nextLambdaSyntheticName(@NotNull String owningClassName) {
        var index = lambdaNameCountersByOwningClass.merge(owningClassName, 1, Integer::sum);
        return "_lambda_" + (index - 1);
    }

    /// Publishes callable-entry var-type-post facts before the first body statement is resolved.
    ///
    /// Parameters are not statement roots, so this complements rather than bypasses the
    /// statement-local var-type-post procedure. Both paths publish through the same overlay,
    /// owner stage, and callable-scoped export batch.
    private void runCallableEntryVarTypePost(
            @NotNull FrontendSuiteContext context,
            @NotNull Node callableOwner
    ) {
        var parameters = callableParameters(callableOwner);
        for (var parameter : parameters) {
            publishCallableEntryParameterSlotType(context, parameter);
            // Source-function defaults (excluding `_init`) are owned by the parameter-default
            // metadata sweep; constructors, `_init` functions, and lambdas keep the fail-closed
            // binding/chain diagnostics here.
            if (!(callableOwner instanceof FunctionDeclaration functionDeclaration)
                    || functionDeclaration.name().trim().equals("_init")) {
                reportUnsupportedParameterDefault(context, parameter);
            }
        }
        // Make every parameter visible to the first statement without publishing stable facts.
        context.typedEnvironment().flushPendingFacts();
    }

    private void publishCallableEntryParameterSlotType(
            @NotNull FrontendSuiteContext context,
            @NotNull Parameter parameter
    ) {
        var scope = context.analysisData().scopesByAst().get(parameter);
        if (!(scope instanceof CallableScope callableScope)) {
            throw new IllegalStateException("Parameter '" + parameter.name().trim() + "' has no published callable scope");
        }
        var slot = callableScope.resolveValueHere(parameter.name().trim());
        if (slot == null || slot.kind() != ScopeValueKind.PARAMETER || slot.declaration() != parameter) {
            throw new IllegalStateException("Parameter '" + parameter.name().trim() + "' inventory slot drifted");
        }
        var baselineType = context.interfaceSurface().typedLexicalBaseline().typeFor(parameter);
        if (baselineType == null) {
            throw new IllegalStateException("Parameter '" + parameter.name().trim() + "' is missing typed baseline");
        }
        context.typedEnvironment().putSlotType(FrontendSemanticStage.VAR_TYPE_POST, parameter, baselineType);
    }

    private static void reportUnsupportedParameterDefault(
            @NotNull FrontendSuiteContext context,
            @NotNull Parameter parameter
    ) {
        if (parameter.defaultValue() == null) {
            return;
        }
        context.diagnosticManager().error(
                UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                "Binding analysis is not supported in parameter default",
                context.sourcePath(),
                FrontendRange.fromAstRange(parameter.defaultValue().range())
        );
        context.diagnosticManager().error(
                UNSUPPORTED_CHAIN_ROUTE_CATEGORY,
                "Chain binding analysis is not supported in parameter default",
                context.sourcePath(),
                FrontendRange.fromAstRange(parameter.defaultValue().range())
        );
    }

    private void resolvePropertyInitializer(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull VariableDeclaration propertyInitializer,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var propertyContext = FrontendPropertyInitializerSupport.contextOrNull(
                analysisData.scopesByAst(),
                propertyInitializer
        );
        if (propertyContext == null) {
            return;
        }
        var classScope = propertyContext.declaringClassScope();
        var environment = new FrontendTypedLexicalEnvironment(
                classScope,
                analysisData,
                null,
                interfaceSurface.typedLexicalBaseline()
        );
        var context = new FrontendSuiteContext(
                sourcePathFor(interfaceSurface, propertyInitializer, analysisData),
                propertyInitializer,
                null,
                classScope,
                null,
                FrontendPropertyInitializerSupport.restrictionFor(propertyInitializer),
                propertyInitializer.isStatic(),
                propertyContext,
                interfaceSurface,
                environment,
                analysisData,
                diagnosticManager,
                classRegistry,
                null,
                null,
                // Property initializers stay fail-closed for lambdas: no nested resolve trigger.
                null,
                // Property initializers bypass the visible-value resolver entirely
                // (`resolveVisibleValue` class-scope shortcut), so the domain is never consulted.
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        );
        statementResolver.resolvePropertyInitializer(context, propertyInitializer);
        // Property initializers are independent roots and do not join a callable export batch.
        context.typedEnvironment().exportPatchTransaction().applyTo(analysisData);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
    }

    /// Resolves a child block with its own overlay while sharing the parent's callable export batch.
    private void resolveChildSuite(@NotNull FrontendSuiteContext parentContext, @NotNull Block childBlock) {
        var blockScope = requireBlockScope(parentContext, childBlock);
        resolveSuite(parentContext.withChildBlock(childBlock, blockScope), childBlock);
    }

    private static @NotNull BlockScope requireBlockScope(
            @NotNull FrontendSuiteContext context,
            @NotNull Block block
    ) {
        var scope = context.analysisData().scopesByAst().get(block);
        if (scope instanceof BlockScope blockScope) {
            return blockScope;
        }
        throw new IllegalStateException("Suite body has no published BlockScope");
    }

    private static @Nullable Block callableBody(@NotNull Node callableOwner) {
        return switch (callableOwner) {
            case FunctionDeclaration functionDeclaration -> functionDeclaration.body();
            case ConstructorDeclaration constructorDeclaration -> constructorDeclaration.body();
            case LambdaExpression lambdaExpression -> lambdaExpression.body();
            default -> null;
        };
    }

    private static @NotNull List<Parameter> callableParameters(@NotNull Node callableOwner) {
        return switch (callableOwner) {
            case FunctionDeclaration functionDeclaration -> functionDeclaration.parameters();
            case ConstructorDeclaration constructorDeclaration -> constructorDeclaration.parameters();
            case LambdaExpression lambdaExpression -> lambdaExpression.parameters();
            default -> List.of();
        };
    }

    private static @NotNull ResolveRestriction restrictionForCallable(@NotNull Node callableOwner) {
        if (callableOwner instanceof FunctionDeclaration functionDeclaration && functionDeclaration.isStatic()) {
            return ResolveRestriction.staticContext();
        }
        return ResolveRestriction.instanceContext();
    }

    private static boolean isStaticCallable(@NotNull Node callableOwner) {
        return callableOwner instanceof FunctionDeclaration functionDeclaration && functionDeclaration.isStatic();
    }

    /// Shared with `FrontendParameterDefaultMetadataOwner`, whose island contexts need the same
    /// per-entry-root source path resolution.
    static @NotNull Path sourcePathFor(
            @NotNull FrontendInterfaceSurface interfaceSurface,
            @NotNull Node entryRoot,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var sourcePath = interfaceSurface.suiteEntryRoots().sourcePathFor(entryRoot);
        if (sourcePath != null) {
            return sourcePath;
        }
        return analysisData.moduleSkeleton().sourceClassRelations().getFirst().unit().path();
    }
}