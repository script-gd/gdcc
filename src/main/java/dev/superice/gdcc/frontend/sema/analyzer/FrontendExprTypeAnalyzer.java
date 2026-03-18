package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
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
import java.util.ArrayList;
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
        private final @NotNull IdentityHashMap<Node, Node> parentByNode = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedExpressionRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedDeferredRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedUnsupportedRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedDiscardedRoots = new IdentityHashMap<>();
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;

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
                    classRegistry,
                    this::resolveExpressionDependency
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
            var expressionType = publishExpressionType(expression);
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
            if (supportedExecutableBlockDepth <= 0 || variableDeclaration.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() != DeclarationKind.VAR) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishExpressionType(variableDeclaration.value());
            backfillInferredLocalType(variableDeclaration);
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
        /// static-route heads in MVP.
        private @Nullable FrontendExpressionType publishExpressionType(@Nullable Expression expression) {
            if (expression == null) {
                return null;
            }
            var published = expressionTypes.get(expression);
            if (published != null) {
                return published;
            }
            var computed = resolveExpressionType(expression);
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
        /// not first-class value expressions in MVP. Skipping publication keeps static-route heads
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
            return switch (expression) {
                case LiteralExpression literalExpression -> resolveLiteralExpressionType(literalExpression);
                case SelfExpression selfExpression -> resolveSelfExpressionType(selfExpression);
                case IdentifierExpression identifierExpression -> resolveIdentifierExpressionType(identifierExpression);
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                case AssignmentExpression assignmentExpression -> resolveAssignmentExpressionType(assignmentExpression);
                case CallExpression callExpression -> resolveCallExpressionType(callExpression);
                case SubscriptExpression subscriptExpression -> resolveSubscriptExpressionType(subscriptExpression);
                case LambdaExpression lambdaExpression -> deferredExpression(
                        lambdaExpression,
                        "Lambda expression typing is deferred until lambda semantics are implemented"
                );
                default -> resolveGenericDeferredExpressionType(expression);
            };
        }

        private @NotNull FrontendExpressionType resolveLiteralExpressionType(
                @NotNull LiteralExpression literalExpression
        ) {
            var literalType = chainReduction.headReceiverSupport().resolveLiteralType(literalExpression);
            if (literalType != null) {
                return FrontendExpressionType.resolved(literalType);
            }
            return FrontendExpressionType.failed(
                    "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
            );
        }

        private @NotNull FrontendExpressionType resolveSelfExpressionType(@NotNull Node selfNode) {
            return FrontendChainStatusBridge.toPublishedExpressionType(
                    chainReduction.headReceiverSupport().resolveSelfReceiver(selfNode)
            );
        }

        private @NotNull FrontendExpressionType resolveIdentifierExpressionType(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var binding = analysisData.symbolBindings().get(identifierExpression);
            if (binding == null) {
                return FrontendExpressionType.failed(
                        "No published binding fact is available for identifier '" + identifierExpression.name() + "'"
                );
            }
            return switch (binding.kind()) {
                case SELF -> resolveSelfExpressionType(identifierExpression);
                case PARAMETER, LOCAL_VAR, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON, GLOBAL_ENUM -> {
                    var currentScope = scopesByAst.get(identifierExpression);
                    if (currentScope == null) {
                        yield FrontendExpressionType.unsupported(
                                "Identifier '" + identifierExpression.name() + "' is inside a skipped subtree"
                        );
                    }
                    var valueResult = currentScope.resolveValue(identifierExpression.name(), currentRestriction);
                    if (valueResult.isAllowed()) {
                        yield FrontendExpressionType.resolved(valueResult.requireValue().type());
                    }
                    if (valueResult.isBlocked()) {
                        yield FrontendExpressionType.blocked(
                                valueResult.requireValue().type(),
                                "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                        );
                    }
                    yield FrontendExpressionType.failed(
                            "Published value binding '" + identifierExpression.name() + "' is no longer visible"
                    );
                }
                case TYPE_META -> FrontendExpressionType.failed(
                        "Type-meta identifier '" + identifierExpression.name()
                                + "' cannot be consumed as an ordinary value; use a static route such as '"
                                + identifierExpression.name() + ".build(...)', '" + identifierExpression.name()
                                + ".new()', or a static constant access"
                );
                case METHOD, STATIC_METHOD, UTILITY_FUNCTION ->
                        resolveCallableIdentifierExpressionType(identifierExpression);
                case UNKNOWN, LITERAL -> FrontendExpressionType.failed(
                        "Identifier '" + identifierExpression.name() + "' does not resolve to a typed value"
                );
            };
        }

        private @NotNull FrontendExpressionType resolveCallableIdentifierExpressionType(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var currentScope = scopesByAst.get(identifierExpression);
            if (currentScope == null) {
                return FrontendExpressionType.unsupported(
                        "Callable expression '" + identifierExpression.name() + "' is inside a skipped subtree"
                );
            }
            var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction);
            var callableType = new GdCallableType();
            if (functionResult.isAllowed()) {
                return FrontendExpressionType.resolved(callableType);
            }
            if (functionResult.isBlocked()) {
                return FrontendExpressionType.blocked(
                        callableType,
                        "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                );
            }
            return FrontendExpressionType.failed(
                    "Published callable binding '" + identifierExpression.name() + "' is no longer visible"
            );
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

        private @NotNull FrontendExpressionType resolveAssignmentExpressionType(
                @NotNull AssignmentExpression assignmentExpression
        ) {
            var leftType = publishExpressionType(assignmentExpression.left());
            var rightType = publishExpressionType(assignmentExpression.right());
            var dependencyIssue = firstNonResolvedDependency(leftType, rightType);
            return Objects.requireNonNullElseGet(dependencyIssue, () -> deferredExpression(
                    assignmentExpression,
                    "Assignment expression typing is deferred until assignment semantics are implemented"
            ));
        }

        private @NotNull FrontendExpressionType resolveCallExpressionType(@NotNull CallExpression callExpression) {
            if (callExpression.callee() instanceof IdentifierExpression bareCallee) {
                var calleeType = publishExpressionType(bareCallee);
                if (calleeType != null && calleeType.status() != FrontendExpressionTypeStatus.RESOLVED) {
                    return calleeType;
                }
                var argumentResolution = resolveCallArgumentTypes(
                        bareCallee.name(),
                        callExpression.arguments()
                );
                if (argumentResolution.issue() != null) {
                    return argumentResolution.issue();
                }
                return resolveBareIdentifierCallExpression(
                        callExpression,
                        bareCallee,
                        argumentResolution.argumentTypes()
                );
            }

            var calleeType = publishExpressionType(callExpression.callee());
            var argumentResolution = resolveCallArgumentTypes(
                    callExpression.callee().getClass().getSimpleName(),
                    callExpression.arguments()
            );
            if (argumentResolution.issue() != null) {
                return argumentResolution.issue();
            }
            if (calleeType == null) {
                return unsupportedExpression(
                        callExpression,
                        "Call expression is inside a skipped subtree"
                );
            }
            return switch (calleeType.status()) {
                case RESOLVED -> calleeType.publishedType() instanceof GdCallableType
                        ? unsupportedExpression(
                        callExpression,
                        "Direct invocation of callable values is not implemented yet unless the callee is a bare identifier"
                )
                        : failedExpression(
                        callExpression,
                        "Call target does not resolve to a callable value"
                );
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED, DYNAMIC -> calleeType;
            };
        }

        private @NotNull FrontendExpressionType resolveSubscriptExpressionType(
                @NotNull SubscriptExpression subscriptExpression
        ) {
            var baseType = publishExpressionType(subscriptExpression.base());
            var dependencyIssue = firstNonResolvedDependency(baseType);
            if (dependencyIssue != null) {
                return dependencyIssue;
            }
            var argumentResolution = resolveCallArgumentTypes(
                    "subscript",
                    subscriptExpression.arguments()
            );
            if (argumentResolution.issue() != null) {
                return argumentResolution.issue();
            }
            return deferredExpression(
                    subscriptExpression,
                    "Subscript expression typing is deferred until subscript semantics are implemented"
            );
        }

        private @NotNull FrontendExpressionType resolveGenericDeferredExpressionType(@NotNull Expression expression) {
            var dependencyIssue = publishNestedExpressionChildren(expression);
            return Objects.requireNonNullElseGet(dependencyIssue, () -> deferredExpression(
                    expression,
                    "Expression type for " + expression.getClass().getSimpleName()
                            + " is deferred until milestone-G coverage reaches this node kind"
            ));
        }

        private @Nullable FrontendChainReductionHelper.ReductionResult reduceAttributeExpression(
                @NotNull AttributeExpression attributeExpression
        ) {
            return chainReduction.reduce(attributeExpression).result();
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

        /// Bare-call typing deliberately mirrors chain-call argument handling: resolved types flow
        /// through unchanged, dynamic arguments widen to `Variant`, and the first blocked/deferred/
        /// failed/unsupported argument stops the bare call without adding a duplicate root diagnostic.
        private @NotNull CallArgumentResolution resolveCallArgumentTypes(
                @NotNull String calleeDisplayName,
                @NotNull List<? extends Expression> arguments
        ) {
            var argumentTypes = new ArrayList<GdType>(arguments.size());
            for (var index = 0; index < arguments.size(); index++) {
                var argumentType = publishExpressionType(arguments.get(index));
                if (argumentType == null) {
                    return new CallArgumentResolution(
                            List.of(),
                            unsupportedExpression(
                                    arguments.get(index),
                                    "Argument #" + (index + 1) + " of '" + calleeDisplayName
                                            + "' is inside a skipped subtree"
                            )
                    );
                }
                switch (argumentType.status()) {
                    case RESOLVED, DYNAMIC -> argumentTypes.add(argumentType.publishedType());
                    case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> {
                        return new CallArgumentResolution(List.of(), argumentType);
                    }
                }
            }
            return new CallArgumentResolution(List.copyOf(argumentTypes), null);
        }

        private @NotNull FrontendExpressionType resolveBareIdentifierCallExpression(
                @NotNull CallExpression callExpression,
                @NotNull IdentifierExpression bareCallee,
                @NotNull List<GdType> argumentTypes
        ) {
            var currentScope = scopesByAst.get(bareCallee);
            if (currentScope == null) {
                return unsupportedExpression(
                        callExpression,
                        "Bare call '" + bareCallee.name() + "(...)' is inside a skipped subtree"
                );
            }
            var functionResult = currentScope.resolveFunctions(bareCallee.name(), currentRestriction);
            if (functionResult.isAllowed()) {
                var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
                if (overloadSelection.selected() != null) {
                    return FrontendExpressionType.resolved(overloadSelection.selected().getReturnType());
                }
                return failedExpression(
                        callExpression,
                        Objects.requireNonNull(overloadSelection.detailReason(), "detailReason must not be null")
                );
            }
            if (functionResult.isBlocked()) {
                var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
                var blockedReturnType = overloadSelection.selected() != null
                        ? overloadSelection.selected().getReturnType()
                        : null;
                return FrontendExpressionType.blocked(
                        blockedReturnType,
                        "Binding '" + bareCallee.name() + "' is not accessible in the current context"
                );
            }
            return FrontendExpressionType.failed(
                    "Published bare callee binding '" + bareCallee.name() + "' is no longer visible"
            );
        }

        private @Nullable FrontendExpressionType firstNonResolvedDependency(
                @Nullable FrontendExpressionType... dependencies
        ) {
            for (var dependency : dependencies) {
                if (dependency == null || dependency.status() == FrontendExpressionTypeStatus.RESOLVED) {
                    continue;
                }
                if (dependency.status() == FrontendExpressionTypeStatus.DYNAMIC) {
                    continue;
                }
                return dependency;
            }
            return null;
        }

        private @Nullable FrontendExpressionType publishNestedExpressionChildren(@NotNull Node node) {
            for (var child : node.getChildren()) {
                if (child instanceof Expression childExpression) {
                    var publishedChild = publishExpressionType(childExpression);
                    var dependencyIssue = firstNonResolvedDependency(publishedChild);
                    if (dependencyIssue != null) {
                        return dependencyIssue;
                    }
                    continue;
                }
                var nestedIssue = publishNestedExpressionChildren(child);
                if (nestedIssue != null) {
                    return nestedIssue;
                }
            }
            return null;
        }

        private @NotNull FrontendExpressionType failedExpression(
                @NotNull Node root,
                @NotNull String detailReason
        ) {
            reportExpressionError(root, detailReason);
            return FrontendExpressionType.failed(detailReason);
        }

        private @NotNull FrontendExpressionType deferredExpression(
                @NotNull Node root,
                @NotNull String detailReason
        ) {
            reportDeferredExpression(root, detailReason);
            return FrontendExpressionType.deferred(detailReason);
        }

        private @NotNull FrontendExpressionType unsupportedExpression(
                @NotNull Node root,
                @NotNull String detailReason
        ) {
            reportUnsupportedExpression(root, detailReason);
            return FrontendExpressionType.unsupported(detailReason);
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
            diagnosticManager.warning(
                    UNSUPPORTED_EXPRESSION_ROUTE_CATEGORY,
                    Objects.requireNonNull(detailReason, "detailReason must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(root.range())
            );
        }

        private void reportDiscardedExpressionWarning(
                @NotNull Expression expression,
                @Nullable FrontendExpressionType expressionType
        ) {
            if (expressionType == null || expressionType.status() != FrontendExpressionTypeStatus.RESOLVED) {
                return;
            }
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

        private @NotNull CallableOverloadSelection selectCallableOverload(
                @NotNull List<? extends FunctionDef> overloadSet,
                @NotNull List<GdType> argumentTypes
        ) {
            var applicable = overloadSet.stream()
                    .filter(callable -> matchesCallableArguments(callable, argumentTypes))
                    .toList();
            if (applicable.size() == 1) {
                return new CallableOverloadSelection(applicable.getFirst(), null);
            }
            if (applicable.size() > 1) {
                return new CallableOverloadSelection(
                        null,
                        "Ambiguous bare call overload: " + renderCallableSignatures(applicable)
                );
            }
            var detailReason = overloadSet.isEmpty()
                    ? "Bare call resolves to an empty overload set"
                    : "No applicable overload for bare call: "
                    + buildCallableMismatchReason(overloadSet.getFirst(), argumentTypes)
                    + ". candidates: " + renderCallableSignatures(overloadSet);
            return new CallableOverloadSelection(null, detailReason);
        }

        private boolean matchesCallableArguments(
                @NotNull FunctionDef callable,
                @NotNull List<GdType> argumentTypes
        ) {
            var parameters = List.copyOf(callable.getParameters());
            var fixedCount = parameters.size();
            var providedCount = argumentTypes.size();
            if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
                return false;
            }
            if (!callable.isVararg() && providedCount > fixedCount) {
                return false;
            }
            var fixedPrefixCount = Math.min(providedCount, fixedCount);
            for (var index = 0; index < fixedPrefixCount; index++) {
                if (!classRegistry.checkAssignable(argumentTypes.get(index), parameters.get(index).getType())) {
                    return false;
                }
            }
            if (!callable.isVararg()) {
                return true;
            }
            for (var index = fixedCount; index < providedCount; index++) {
                if (!classRegistry.checkAssignable(argumentTypes.get(index), GdVariantType.VARIANT)) {
                    return false;
                }
            }
            return true;
        }

        private @NotNull String buildCallableMismatchReason(
                @NotNull FunctionDef callable,
                @NotNull List<GdType> argumentTypes
        ) {
            var parameters = List.copyOf(callable.getParameters());
            var fixedCount = parameters.size();
            var providedCount = argumentTypes.size();
            if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
                var missingParameterIndex = firstMissingRequiredParameter(parameters, providedCount);
                return "missing required parameter #" + (missingParameterIndex + 1) + " ('"
                        + parameters.get(missingParameterIndex).getName() + "')";
            }
            if (!callable.isVararg() && providedCount > fixedCount) {
                return "expected " + fixedCount + " arguments, got " + providedCount;
            }
            var fixedPrefixCount = Math.min(providedCount, fixedCount);
            for (var index = 0; index < fixedPrefixCount; index++) {
                var argumentType = argumentTypes.get(index);
                var parameter = parameters.get(index);
                if (!classRegistry.checkAssignable(argumentType, parameter.getType())) {
                    return "argument #" + (index + 1) + " of type '" + argumentType.getTypeName()
                            + "' is not assignable to parameter '" + parameter.getName()
                            + "' of type '" + parameter.getType().getTypeName() + "'";
                }
            }
            if (callable.isVararg()) {
                for (var index = fixedCount; index < providedCount; index++) {
                    var argumentType = argumentTypes.get(index);
                    if (!classRegistry.checkAssignable(argumentType, GdVariantType.VARIANT)) {
                        return "vararg argument #" + (index + 1) + " must be Variant, got '"
                                + argumentType.getTypeName() + "'";
                    }
                }
            }
            return "no compatible signature found";
        }

        private boolean canOmitTrailingParameters(
                @NotNull List<? extends ParameterDef> parameters,
                int providedCount
        ) {
            for (var index = providedCount; index < parameters.size(); index++) {
                if (parameters.get(index).getDefaultValueFunc() == null) {
                    return false;
                }
            }
            return true;
        }

        private int firstMissingRequiredParameter(
                @NotNull List<? extends ParameterDef> parameters,
                int providedCount
        ) {
            for (var index = providedCount; index < parameters.size(); index++) {
                if (parameters.get(index).getDefaultValueFunc() == null) {
                    return index;
                }
            }
            return providedCount;
        }

        private @NotNull String renderCallableSignatures(@NotNull List<? extends FunctionDef> callables) {
            var signatures = new ArrayList<String>(callables.size());
            for (var callable : callables) {
                var args = new ArrayList<String>();
                for (var parameter : callable.getParameters()) {
                    args.add(parameter.getType().getTypeName());
                }
                if (callable.isVararg()) {
                    args.add("...");
                }
                signatures.add(callable.getName() + "(" + String.join(", ", args) + ")");
            }
            return String.join("; ", signatures);
        }

        private boolean isNotPublished(@Nullable Node astNode) {
            return astNode == null || !scopesByAst.containsKey(astNode);
        }

        public @NotNull ClassRegistry getClassRegistry() {
            return classRegistry;
        }

        private record CallArgumentResolution(
                @NotNull List<GdType> argumentTypes,
                @Nullable FrontendExpressionType issue
        ) {
            private CallArgumentResolution {
                argumentTypes = List.copyOf(argumentTypes);
            }
        }

        private record CallableOverloadSelection(
                @Nullable FunctionDef selected,
                @Nullable String detailReason
        ) {
        }
    }
}
