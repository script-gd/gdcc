package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendAssignmentSemanticSupport;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Publishes frontend expression typing facts after chain/member/call results are already visible.
///
/// The phase rebuilds `expressionTypes()` in place so nested chain reduction can immediately consume
/// freshly published inner expression facts without introducing a second temporary table.
public class FrontendExprTypeAnalyzer {
    private static final @NotNull String EXPRESSION_RESOLUTION_CATEGORY = "sema.expression_resolution";
    private static final @NotNull String DEFERRED_EXPRESSION_RESOLUTION_CATEGORY =
            "sema.deferred_expression_resolution";
    private static final @NotNull String UNSUPPORTED_EXPRESSION_ROUTE_CATEGORY =
            "sema.unsupported_expression_route";
    private static final @NotNull String DISCARDED_EXPRESSION_CATEGORY = "sema.discarded_expression";

    public void analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        var scopesByAst = analysisData.scopesByAst();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        var expressionTypes = analysisData.expressionTypes();
        expressionTypes.clear();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerExprTypePublisher(
                    sourceClassRelation.unit().path(),
                    classRegistry,
                    analysisData,
                    scopesByAst,
                    expressionTypes,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
        analysisData.updateExpressionTypes(expressionTypes);
    }

    private static final class AstWalkerExprTypePublisher implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull ClassRegistry classRegistry;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull FrontendChainReductionFacade chainReduction;
        private final @NotNull FrontendAssignmentSemanticSupport assignmentSemanticSupport;
        private final @NotNull FrontendExpressionSemanticSupport expressionSemanticSupport;
        private final @NotNull IdentityHashMap<Node, Node> parentByNode = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedExpressionRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedDeferredRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedUnsupportedRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedDiscardedRoots = new IdentityHashMap<>();
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;
        private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext;

        private AstWalkerExprTypePublisher(
                @NotNull Path sourcePath,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
            chainReduction = new FrontendChainReductionFacade(
                    analysisData,
                    scopesByAst,
                    () -> currentRestriction,
                    () -> currentStaticContext,
                    () -> currentPropertyInitializerContext,
                    classRegistry,
                    this::resolveExpressionDependency
            );
            assignmentSemanticSupport = new FrontendAssignmentSemanticSupport(
                    analysisData.symbolBindings(),
                    scopesByAst,
                    () -> currentRestriction,
                    classRegistry,
                    chainReduction
            );
            expressionSemanticSupport = new FrontendExpressionSemanticSupport(
                    analysisData.symbolBindings(),
                    scopesByAst,
                    () -> currentRestriction,
                    () -> currentPropertyInitializerContext,
                    classRegistry,
                    chainReduction::headReceiverSupport
            );
        }

        private void walk(@NotNull SourceFile sourceFile) {
            indexSubtree(Objects.requireNonNull(sourceFile, "sourceFile must not be null"), null);
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
        public @NotNull FrontendASTTraversalDirective handleExpressionStatement(
                @NotNull ExpressionStatement expressionStatement
        ) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var expression = expressionStatement.expression();
            var expressionType = publishStatementExpressionType(expression);
            reportDiscardedExpressionWarning(expression, expressionType);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0 || returnStatement.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishExpressionType(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishExpressionType(assertStatement.condition());
            if (assertStatement.message() != null) {
                publishExpressionType(assertStatement.message());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedExecutableBlockDepth > 0) {
                if (variableDeclaration.value() == null) {
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                if (variableDeclaration.kind() != DeclarationKind.VAR) {
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                publishExpressionType(variableDeclaration.value());
                backfillInferredLocalType(variableDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishPropertyInitializerExpressionType(variableDeclaration);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishExpressionType(ifStatement.condition());
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
            publishExpressionType(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishExpressionType(whileStatement.condition());
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedExecutableBlockDepth > 0) {
                publishExpressionType(matchStatement.value());
            }
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

        /// Property initializers reuse ordinary expression typing, but the temporary restriction must
        /// come from the declaring property rather than from an executable-body context.
        private void publishPropertyInitializerExpressionType(@NotNull VariableDeclaration variableDeclaration) {
            var initializer = Objects.requireNonNull(
                    variableDeclaration.value(),
                    "property initializer value must not be null"
            );
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentRestriction = FrontendPropertyInitializerSupport.restrictionFor(variableDeclaration);
            currentStaticContext = variableDeclaration.isStatic();
            currentPropertyInitializerContext = FrontendPropertyInitializerSupport.contextFor(
                    scopesByAst,
                    variableDeclaration
            );
            try {
                publishExpressionType(initializer);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentRestriction = previousRestriction;
                currentStaticContext = previousStaticContext;
            }
        }

        private void indexSubtree(@NotNull Node node, @Nullable Node parent) {
            Objects.requireNonNull(node, "node must not be null");
            if (parent != null) {
                parentByNode.put(node, parent);
            }
            for (var child : node.getChildren()) {
                indexSubtree(child, node);
            }
        }

        /// Ensures the ordinary value expression type for `expression` has been published when one
        /// should exist in `expressionTypes()`.
        ///
        /// Callers use this for its side effect only: populate the published side table before a
        /// parent expression needs to consume nested facts. Bare `TYPE_META` identifiers are the
        /// one intentional exception: they may be computed transiently for the current reduction,
        /// but they are not published as ordinary value expression facts because they only serve as
        /// static-route heads in the current frontend contract.
        private @Nullable FrontendExpressionType publishExpressionType(@Nullable Expression expression) {
            return publishExpressionType(expression, false);
        }

        private @Nullable FrontendExpressionType publishStatementExpressionType(@Nullable Expression expression) {
            return publishExpressionType(expression, true);
        }

        private @Nullable FrontendExpressionType publishExpressionType(
                @Nullable Expression expression,
                boolean allowStatementResult
        ) {
            if (expression == null) {
                return null;
            }
            var published = expressionTypes.get(expression);
            if (published != null) {
                return published;
            }
            var computed = resolveExpressionType(expression, allowStatementResult);
            publishResolvedExpressionType(expression, computed);
            return computed;
        }

        private void publishResolvedExpressionType(
                @NotNull Expression expression,
                @NotNull FrontendExpressionType computed
        ) {
            if (isRouteHeadOnlyTypeMeta(expression)) {
                return;
            }
            expressionTypes.put(expression, computed);
        }

        /// Bare `TYPE_META` identifiers are valid chain heads such as `Worker.build()` but they are
        /// not first-class ordinary value expressions. Skipping publication keeps static-route heads
        /// out of ordinary `expressionTypes()` consumers and out of `:=` backfill.
        private boolean isRouteHeadOnlyTypeMeta(@NotNull Expression expression) {
            if (!(expression instanceof IdentifierExpression identifierExpression)) {
                return false;
            }
            var binding = analysisData.symbolBindings().get(identifierExpression);
            if (binding == null || binding.kind() != FrontendBindingKind.TYPE_META) {
                return false;
            }
            var parent = parentByNode.get(identifierExpression);
            return parent instanceof AttributeExpression attributeExpression
                    && attributeExpression.base() == identifierExpression;
        }

        /// Supported local `:=` declarations are still inventoried as `Variant` during variable
        /// analysis. Once the RHS expression type is published here, rewrite the block-local slot
        /// only when that published result is stable enough to become the local's value type.
        ///
        /// This backfill intentionally updates only the block-scope inventory slot. It does not
        /// rewrite `expressionTypes()` or `symbolBindings()`, so later consumers can still recover
        /// initializer provenance through the local use-site's `declarationSite()` plus the
        /// initializer expression's own published type and diagnostics.
        private void backfillInferredLocalType(@NotNull VariableDeclaration variableDeclaration) {
            if (!FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())
                    || variableDeclaration.value() == null) {
                return;
            }
            var declarationScope = scopesByAst.get(variableDeclaration);
            if (!(declarationScope instanceof BlockScope blockScope)) {
                return;
            }
            var publishedInitializerType = expressionTypes.get(variableDeclaration.value());
            if (publishedInitializerType == null) {
                return;
            }
            var backfilledType = switch (publishedInitializerType.status()) {
                case RESOLVED, DYNAMIC -> publishedInitializerType.publishedType();
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> null;
            };
            if (backfilledType == null) {
                return;
            }
            blockScope.resetLocalType(variableDeclaration.name().trim(), variableDeclaration, backfilledType);
        }

        private @NotNull FrontendExpressionType resolveExpressionType(@NotNull Expression expression) {
            return resolveExpressionType(expression, false);
        }

        private @NotNull FrontendExpressionType resolveExpressionType(
                @NotNull Expression expression,
                boolean allowStatementResult
        ) {
            return switch (expression) {
                case LiteralExpression literalExpression ->
                        expressionSemanticSupport.resolveLiteralExpressionType(literalExpression).expressionType();
                case SelfExpression selfExpression ->
                        expressionSemanticSupport.resolveSelfExpressionType(selfExpression).expressionType();
                case IdentifierExpression identifierExpression ->
                        expressionSemanticSupport.resolveIdentifierExpressionType(identifierExpression).expressionType();
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                case AssignmentExpression assignmentExpression -> finishSemanticResolution(
                        assignmentExpression,
                        assignmentSemanticSupport.resolveAssignmentExpressionType(
                                assignmentExpression,
                                allowStatementResult
                                        ? FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT
                                        : FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                                this::resolveExpressionDependencyType,
                                false
                        )
                );
                case CallExpression callExpression -> finishSemanticResolution(
                        callExpression,
                        expressionSemanticSupport.resolveCallExpressionType(
                                callExpression,
                                this::resolveExpressionDependencyType,
                                true,
                                false
                        )
                );
                case SubscriptExpression subscriptExpression -> finishSemanticResolution(
                        subscriptExpression,
                        expressionSemanticSupport.resolveSubscriptExpressionType(
                                subscriptExpression,
                                this::resolveExpressionDependencyType,
                                false
                        )
                );
                case LambdaExpression lambdaExpression -> finishSemanticResolution(
                        lambdaExpression,
                        expressionSemanticSupport.resolveLambdaExpressionType(
                                lambdaExpression,
                                this::resolveExpressionDependencyType,
                                false,
                                false
                        )
                );
                default -> finishSemanticResolution(
                        expression,
                        expressionSemanticSupport.resolveRemainingExplicitExpressionType(
                                expression,
                                this::resolveExpressionDependencyType,
                                true,
                                false
                        )
                );
            };
        }

        /// Published final-step facts cover the exact fast path.
        /// When the last step is intentionally omitted because an earlier step already turned the
        /// suffix into blocked/deferred/dynamic recovery, we rerun local reduction to recover the
        /// final expression status without adding another global side table.
        private @NotNull FrontendExpressionType resolveAttributeExpressionType(
                @NotNull AttributeExpression attributeExpression
        ) {
            publishExpressionType(attributeExpression.base());
            for (var step : attributeExpression.steps()) {
                if (step instanceof AttributeCallStep attributeCallStep) {
                    for (var argument : attributeCallStep.arguments()) {
                        publishExpressionType(argument);
                    }
                    continue;
                }
                if (step instanceof AttributeSubscriptStep attributeSubscriptStep) {
                    for (var argument : attributeSubscriptStep.arguments()) {
                        publishExpressionType(argument);
                    }
                }
            }

            var finalStep = attributeExpression.steps().getLast();
            if (finalStep instanceof AttributePropertyStep) {
                var publishedMember = analysisData.resolvedMembers().get(finalStep);
                if (publishedMember != null) {
                    return FrontendChainStatusBridge.toPublishedExpressionType(publishedMember);
                }
            }
            if (finalStep instanceof AttributeCallStep) {
                var publishedCall = analysisData.resolvedCalls().get(finalStep);
                if (publishedCall != null) {
                    return FrontendChainStatusBridge.toPublishedExpressionType(publishedCall);
                }
            }

            var reduced = reduceAttributeExpression(attributeExpression);
            if (reduced == null) {
                return FrontendExpressionType.failed(
                        "No receiver fact is available for attribute expression rooted at "
                                + attributeExpression.base().getClass().getSimpleName()
                );
            }
            return FrontendChainStatusBridge.toPublishedExpressionType(reduced);
        }

        private @Nullable FrontendChainReductionHelper.ReductionResult reduceAttributeExpression(
                @NotNull AttributeExpression attributeExpression
        ) {
            return chainReduction.reduce(attributeExpression).result();
        }

        private @NotNull FrontendExpressionType resolveExpressionDependencyType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return Objects.requireNonNull(
                    publishExpressionType(expression),
                    "publishExpressionType must not return null for non-null expressions"
            );
        }

        private @NotNull FrontendExpressionType finishSemanticResolution(
                @NotNull Node root,
                @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult resolution
        ) {
            return finishSemanticResolution(root, resolution.expressionType(), resolution.rootOwnsOutcome());
        }

        private @NotNull FrontendExpressionType finishSemanticResolution(
                @NotNull Node root,
                @NotNull FrontendExpressionType expressionType,
                boolean rootOwnsOutcome
        ) {
            if (rootOwnsOutcome) {
                switch (expressionType.status()) {
                    case FAILED -> reportExpressionError(root, requireDetailReason(expressionType));
                    case UNSUPPORTED -> reportUnsupportedExpression(root, requireDetailReason(expressionType));
                    case DEFERRED -> reportDeferredExpression(root, requireDetailReason(expressionType));
                    case RESOLVED, BLOCKED, DYNAMIC -> {
                    }
                }
            }
            return expressionType;
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveExpressionDependency(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            var published = expressionTypes.get(expression);
            if (published != null) {
                return FrontendChainStatusBridge.toExpressionTypeResult(published);
            }
            var computed = resolveExpressionType(expression);
            publishResolvedExpressionType(expression, computed);
            return FrontendChainStatusBridge.toExpressionTypeResult(computed);
        }

        private void reportExpressionError(@NotNull Node root, @NotNull String detailReason) {
            if (reportedExpressionRoots.putIfAbsent(root, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.error(
                    EXPRESSION_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(detailReason, "detailReason must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(root.range())
            );
        }

        private void reportDeferredExpression(@NotNull Node root, @NotNull String detailReason) {
            if (reportedDeferredRoots.putIfAbsent(root, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.warning(
                    DEFERRED_EXPRESSION_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(detailReason, "detailReason must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(root.range())
            );
        }

        private void reportUnsupportedExpression(@NotNull Node root, @NotNull String detailReason) {
            if (reportedUnsupportedRoots.putIfAbsent(root, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.error(
                    UNSUPPORTED_EXPRESSION_ROUTE_CATEGORY,
                    Objects.requireNonNull(detailReason, "detailReason must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(root.range())
            );
        }

        private @NotNull String requireDetailReason(@NotNull FrontendExpressionType expressionType) {
            return Objects.requireNonNull(expressionType.detailReason(), "detailReason must not be null");
        }

        private void reportDiscardedExpressionWarning(
                @NotNull Expression expression,
                @Nullable FrontendExpressionType expressionType
        ) {
            if (expressionType == null || expressionType.status() != FrontendExpressionTypeStatus.RESOLVED) {
                return;
            }
            // Successful assignments now publish `RESOLVED(void)` and intentionally share the same
            // quiet path as ordinary `void` calls.
            var publishedType = expressionType.publishedType();
            if (publishedType == null || publishedType instanceof GdVoidType) {
                return;
            }
            if (reportedDiscardedRoots.putIfAbsent(expression, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.warning(
                    DISCARDED_EXPRESSION_CATEGORY,
                    "Discarded expression result of type '" + publishedType.getTypeName() + "'",
                    sourcePath,
                    FrontendRange.fromAstRange(expression.range())
            );
        }

        private boolean isNotPublished(@Nullable Node astNode) {
            return astNode == null || !scopesByAst.containsKey(astNode);
        }

        public @NotNull ClassRegistry getClassRegistry() {
            return classRegistry;
        }
    }
}
