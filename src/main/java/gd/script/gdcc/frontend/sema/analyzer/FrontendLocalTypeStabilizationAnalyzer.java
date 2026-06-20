package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendAssignmentSemanticSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Local type stabilization for supported callable executable bodies.
///
/// The scope is intentionally narrow:
/// - walk supported callable executable bodies
/// - find eligible local `var := initializer`
/// - resolve initializer types through a silent local resolver
/// - explicitly exclude bare `TYPE_META` ordinary-value initializers
/// - explicitly fail-closed for assignment initializers
/// - write back only exact stable local slot types
///
/// The shared semantic pipeline runs this phase after top binding and before chain binding so member
/// resolution consumes exact receiver slots for source-order `:=` aliases, including parameter-to-local
/// aliases such as `var alias := typed_parameter`. Nested supported blocks keep the same contract:
/// child blocks may read parent locals after they have stabilized, but a child declaration only rewrites
/// its own `BlockScope` slot and never backwrites a parent scope.
///
/// This phase deliberately does not:
/// - update `resolvedMembers()`, `resolvedCalls()`, `expressionTypes()`, or `slotTypes()`
/// - emit diagnostics
public class FrontendLocalTypeStabilizationAnalyzer {
    public void analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        run(classRegistry, analysisData, diagnosticManager, true);
    }

    /// Package-private test helper for observing transient initializer typing.
    ///
    /// The contract stays intentionally narrow:
    /// - run the same walker/resolver path as `analyze(...)`
    /// - expose only transient initializer typing results
    /// - keep side tables, diagnostics, and scope slots untouched
    @NotNull ProbeSnapshot probe(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        return run(classRegistry, analysisData, diagnosticManager, false);
    }

    static @Nullable FrontendExpressionType probeAssignmentOrdinaryValueInitializerFailure(
            @NotNull Expression initializer
    ) {
        return assignmentOrdinaryValueInitializerFailure(initializer);
    }

    static void probeStabilizeLocalSlot(
            @NotNull BlockScope blockScope,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull FrontendExpressionType initializerType
    ) {
        stabilizeLocalSlot(blockScope, variableDeclaration, initializerType);
    }

    private @NotNull ProbeSnapshot run(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            boolean writeBackStableSlots
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        var scopesByAst = analysisData.scopesByAst();
        var probes = new ArrayList<ProbeEntry>();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerLocalTypeStabilizer(
                    sourceClassRelation.unit().path(),
                    classRegistry,
                    analysisData,
                    scopesByAst,
                    probes,
                    writeBackStableSlots
            ).walk(sourceClassRelation.unit().ast());
        }
        return new ProbeSnapshot(probes);
    }

    /// Assignment expressions are statement effects in the current frontend contract, not ordinary
    /// value producers. Keep this rejection next to the slot writeback gate so `var x := (a = b)`
    /// remains an explicit local-stabilization fallback to the inventory-seeded `Variant`.
    private static @Nullable FrontendExpressionType assignmentOrdinaryValueInitializerFailure(
            @NotNull Expression initializer
    ) {
        if (!(initializer instanceof AssignmentExpression)) {
            return null;
        }
        return FrontendExpressionType.failed(
                "Assignment initializer cannot stabilize an inferred local because it is not an ordinary value"
        );
    }

    private static void stabilizeLocalSlot(
            @NotNull BlockScope blockScope,
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull FrontendExpressionType initializerType
    ) {
        var stableType = stableLocalTypeOrNull(initializerType);
        if (stableType == null) {
            return;
        }
        blockScope.resetLocalType(variableDeclaration.name().trim(), variableDeclaration, stableType);
    }

    private static @Nullable GdType stableLocalTypeOrNull(
            @NotNull FrontendExpressionType initializerType
    ) {
        // Only exact ordinary values can rewrite the inventory-seeded local slot. Failed,
        // dynamic, deferred, unsupported, and void initializers keep the existing `Variant`.
        if (initializerType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return null;
        }
        var publishedType = initializerType.publishedType();
        if (publishedType instanceof GdVoidType) {
            return null;
        }
        return publishedType;
    }

    record ProbeSnapshot(@NotNull List<ProbeEntry> entries) {
        ProbeSnapshot {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        }

        @Nullable ProbeEntry findVariable(@NotNull String variableName) {
            Objects.requireNonNull(variableName, "variableName must not be null");
            for (var entry : entries) {
                if (entry.variableName().equals(variableName)) {
                    return entry;
                }
            }
            return null;
        }
    }

    record ProbeEntry(
            @NotNull VariableDeclaration declaration,
            @NotNull Expression initializer,
            @NotNull FrontendExpressionType initializerType
    ) {
        ProbeEntry {
            Objects.requireNonNull(declaration, "declaration must not be null");
            Objects.requireNonNull(initializer, "initializer must not be null");
            Objects.requireNonNull(initializerType, "initializerType must not be null");
        }

        @NotNull String variableName() {
            return declaration.name();
        }
    }

    private static final class AstWalkerLocalTypeStabilizer implements ASTNodeHandler {
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull SilentExpressionResolver silentExpressionResolver;
        private final @NotNull List<ProbeEntry> probes;
        private final boolean writeBackStableSlots;
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;

        private AstWalkerLocalTypeStabilizer(
                @NotNull Path sourcePath,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull List<ProbeEntry> probes,
                boolean writeBackStableSlots
        ) {
            var checkedAnalysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            symbolBindings = checkedAnalysisData.symbolBindings();
            this.probes = Objects.requireNonNull(probes, "probes must not be null");
            this.writeBackStableSlots = writeBackStableSlots;
            astWalker = new ASTWalker(this);
            silentExpressionResolver = new SilentExpressionResolver(
                    Objects.requireNonNull(sourcePath, "sourcePath must not be null"),
                    Objects.requireNonNull(classRegistry, "classRegistry must not be null"),
                    checkedAnalysisData,
                    scopesByAst,
                    () -> currentRestriction,
                    () -> currentStaticContext
            );
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(sourceFile);
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkNonExecutableContainerStatements(sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            if (isNotPublished(functionDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(
                    functionDeclaration,
                    functionDeclaration.body(),
                    functionDeclaration.isStatic()
                            ? ResolveRestriction.staticContext()
                            : ResolveRestriction.instanceContext(),
                    functionDeclaration.isStatic()
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            if (isNotPublished(constructorDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(
                    constructorDeclaration,
                    constructorDeclaration.body(),
                    ResolveRestriction.instanceContext(),
                    false
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            var blockScope = eligibleInferredLocalScope(variableDeclaration);
            if (supportedExecutableBlockDepth <= 0 || blockScope == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var initializer = Objects.requireNonNull(
                    variableDeclaration.value(),
                    "eligible inferred local initializer must not be null"
            );
            var typeMetaFailure = typeMetaOrdinaryValueInitializerFailure(initializer);
            if (typeMetaFailure != null) {
                probes.add(new ProbeEntry(variableDeclaration, initializer, typeMetaFailure));
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var assignmentInitializerFailure = assignmentOrdinaryValueInitializerFailure(initializer);
            if (assignmentInitializerFailure != null) {
                probes.add(new ProbeEntry(variableDeclaration, initializer, assignmentInitializerFailure));
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var initializerType = silentExpressionResolver.resolveExpressionType(initializer);
            probes.add(new ProbeEntry(variableDeclaration, initializer, initializerType));
            if (writeBackStableSlots) {
                stabilizeLocalSlot(blockScope, variableDeclaration, initializerType);
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                walkSupportedExecutableBlock(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void walkCallableBody(
                @NotNull Node callableOwner,
                @Nullable Block body,
                @NotNull ResolveRestriction restriction,
                boolean staticContext
        ) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            currentRestriction = Objects.requireNonNull(restriction, "restriction must not be null");
            currentStaticContext = staticContext;
            try {
                walkSupportedExecutableBlock(body);
            } finally {
                currentRestriction = previousRestriction;
                currentStaticContext = previousStaticContext;
            }
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        private void walkNonExecutableContainerStatements(@NotNull List<Statement> statements) {
            var previousDepth = supportedExecutableBlockDepth;
            supportedExecutableBlockDepth = 0;
            try {
                walkStatements(statements);
            } finally {
                supportedExecutableBlockDepth = previousDepth;
            }
        }

        private void walkSupportedExecutableBlock(@Nullable Block block) {
            if (isNotPublished(block)) {
                return;
            }
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private @Nullable BlockScope eligibleInferredLocalScope(@NotNull VariableDeclaration variableDeclaration) {
            if (variableDeclaration.kind() != DeclarationKind.VAR
                    || variableDeclaration.value() == null
                    || !FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())) {
                return null;
            }
            var declarationScope = scopesByAst.get(variableDeclaration);
            if (!(declarationScope instanceof BlockScope blockScope)
                    || !FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                return null;
            }
            return blockScope;
        }

        /// Bare `TYPE_META` is a route head, not an ordinary runtime value. Keep this guard at the
        /// slot-stabilization boundary so `var x := Worker` never depends on the silent resolver's
        /// identifier failure to avoid rewriting `x` away from `Variant`.
        private @Nullable FrontendExpressionType typeMetaOrdinaryValueInitializerFailure(
                @NotNull Expression initializer
        ) {
            if (!(initializer instanceof IdentifierExpression identifierExpression)) {
                return null;
            }
            var binding = symbolBindings.get(identifierExpression);
            if (binding == null || binding.kind() != FrontendBindingKind.TYPE_META) {
                return null;
            }
            return FrontendExpressionType.failed(
                    "Type-meta initializer '" + identifierExpression.name()
                            + "' cannot stabilize an inferred local because it is not an ordinary value"
            );
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }

    /// Silent local expression resolver used only by the local type stabilization scaffold.
    ///
    /// It intentionally mirrors the existing body-phase semantic helper stack, but keeps every
    /// result transient:
    /// - no side-table publication
    /// - no diagnostics
    /// - no scope mutation
    private static final class SilentExpressionResolver {
        private final @NotNull IdentityHashMap<Expression, FrontendExpressionType> expressionTypes =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, FrontendExpressionType> finalizedExpressionTypes =
                new IdentityHashMap<>();
        private final @NotNull FrontendChainReductionFacade chainReduction;
        private final @NotNull FrontendAssignmentSemanticSupport.Context assignmentSemanticContext;
        private final @NotNull FrontendExpressionSemanticSupport expressionSemanticSupport;

        private SilentExpressionResolver(
                @NotNull Path sourcePath,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull Supplier<ResolveRestriction> restrictionSupplier,
                @NotNull BooleanSupplier staticContextSupplier
        ) {
            Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            Objects.requireNonNull(restrictionSupplier, "restrictionSupplier must not be null");
            Objects.requireNonNull(staticContextSupplier, "staticContextSupplier must not be null");

            chainReduction = new FrontendChainReductionFacade(
                    analysisData,
                    scopesByAst,
                    restrictionSupplier,
                    staticContextSupplier,
                    classRegistry,
                    this::resolveExpressionDependency
            );
            assignmentSemanticContext = FrontendAssignmentSemanticSupport.createContext(
                    analysisData.symbolBindings(),
                    scopesByAst,
                    analysisData.moduleSkeleton(),
                    restrictionSupplier,
                    classRegistry,
                    chainReduction
            );
            expressionSemanticSupport = new FrontendExpressionSemanticSupport(
                    analysisData.symbolBindings(),
                    scopesByAst,
                    restrictionSupplier,
                    classRegistry,
                    chainReduction::headReceiverSupport
            );
        }

        private @NotNull FrontendExpressionType resolveExpressionType(@NotNull Expression expression) {
            return resolveExpressionType(expression, false);
        }

        private @NotNull FrontendExpressionType resolveExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            var cache = finalizeWindow ? finalizedExpressionTypes : expressionTypes;
            var cached = cache.get(expression);
            if (cached != null) {
                return cached;
            }
            var computed = switch (expression) {
                case LiteralExpression literalExpression ->
                        expressionSemanticSupport.resolveLiteralExpressionType(literalExpression).expressionType();
                case SelfExpression selfExpression ->
                        expressionSemanticSupport.resolveSelfExpressionType(selfExpression).expressionType();
                case IdentifierExpression identifierExpression ->
                        expressionSemanticSupport.resolveIdentifierExpressionType(identifierExpression).expressionType();
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                case AssignmentExpression assignmentExpression ->
                        FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                                assignmentSemanticContext,
                                assignmentExpression,
                                FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                                this::resolveExpressionDependencyType,
                                finalizeWindow
                        ).expressionType();
                case CallExpression callExpression -> expressionSemanticSupport.resolveCallExpressionType(
                        callExpression,
                        this::resolveExpressionDependencyType,
                        true,
                        finalizeWindow
                ).expressionType();
                case SubscriptExpression subscriptExpression ->
                        expressionSemanticSupport.resolveSubscriptExpressionType(
                                subscriptExpression,
                                this::resolveExpressionDependencyType,
                                finalizeWindow
                        ).expressionType();
                case LambdaExpression lambdaExpression -> expressionSemanticSupport.resolveLambdaExpressionType(
                        lambdaExpression,
                        this::resolveExpressionDependencyType,
                        false,
                        finalizeWindow
                ).expressionType();
                case UnaryExpression unaryExpression -> expressionSemanticSupport.resolveUnaryExpressionType(
                        unaryExpression,
                        this::resolveExpressionDependencyType,
                        finalizeWindow
                ).expressionType();
                case BinaryExpression binaryExpression -> expressionSemanticSupport.resolveBinaryExpressionType(
                        binaryExpression,
                        this::resolveExpressionDependencyType,
                        finalizeWindow
                ).expressionType();
                default -> expressionSemanticSupport.resolveRemainingExplicitExpressionType(
                        expression,
                        this::resolveExpressionDependencyType,
                        true,
                        finalizeWindow
                ).expressionType();
            };
            cache.put(expression, computed);
            if (finalizeWindow) {
                expressionTypes.put(expression, computed);
            }
            return computed;
        }

        private @NotNull FrontendExpressionType resolveAttributeExpressionType(
                @NotNull AttributeExpression attributeExpression
        ) {
            var reduced = chainReduction.reduce(attributeExpression).result();
            if (reduced == null) {
                return FrontendExpressionType.unsupported(
                        "Nested chain expression is inside an unsupported or skipped subtree"
                );
            }
            return FrontendChainStatusBridge.toPublishedExpressionType(reduced);
        }

        private @NotNull FrontendExpressionType resolveExpressionDependencyType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return resolveExpressionType(expression, finalizeWindow);
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveExpressionDependency(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return FrontendChainStatusBridge.toExpressionTypeResult(
                    resolveExpressionType(expression, finalizeWindow)
            );
        }
    }
}
