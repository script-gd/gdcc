package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
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

import java.util.List;
import java.util.Objects;

/// Publishes frontend expression typing facts after chain/member/call results are already visible.
///
/// The phase rebuilds `expressionTypes()` in place so nested chain reduction can immediately consume
/// freshly published inner expression facts without introducing a second temporary table.
public class FrontendExprTypeAnalyzer {
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
                    classRegistry,
                    analysisData,
                    scopesByAst,
                    expressionTypes
            ).walk(sourceClassRelation.unit().ast());
        }
        analysisData.updateExpressionTypes(expressionTypes);
    }

    private static final class AstWalkerExprTypePublisher implements ASTNodeHandler {
        private final @NotNull ClassRegistry classRegistry;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull FrontendChainReductionFacade chainReduction;
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;

        private AstWalkerExprTypePublisher(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes
        ) {
            this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
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
            publishExpressionType(expressionStatement.expression());
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

        /// Ensures the ordinary value expression type for `expression` has been published when one
        /// should exist in `expressionTypes()`.
        ///
        /// Callers use this for its side effect only: populate the published side table before a
        /// parent expression needs to consume nested facts. Bare `TYPE_META` identifiers are the
        /// one intentional exception: they may be computed transiently for the current reduction,
        /// but they are not published as ordinary value expression facts because they only serve as
        /// static-route heads in MVP.
        private void publishExpressionType(@Nullable Expression expression) {
            if (expression == null) {
                return;
            }
            var published = expressionTypes.get(expression);
            if (published != null) {
                return;
            }
            var computed = resolveExpressionType(expression);
            publishResolvedExpressionType(expression, computed);
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
            return binding != null && binding.kind() == FrontendBindingKind.TYPE_META;
        }

        /// Supported local `:=` declarations are still inventoried as `Variant` during variable
        /// analysis. Once the RHS expression type is published here, rewrite the block-local slot
        /// only when that published result is stable enough to become the local's value type.
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
                case TYPE_META -> FrontendExpressionType.unsupported(
                        "Type-meta identifier '" + identifierExpression.name()
                                + "' does not materialize a value type without an explicit static route"
                );
                case METHOD, STATIC_METHOD, UTILITY_FUNCTION -> FrontendExpressionType.deferred(
                        "Bare callable expression '" + identifierExpression.name()
                                + "' is deferred until bare callable semantics are implemented"
                );
                case UNKNOWN, LITERAL -> FrontendExpressionType.failed(
                        "Identifier '" + identifierExpression.name() + "' does not resolve to a typed value"
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
            publishExpressionType(assignmentExpression.left());
            publishExpressionType(assignmentExpression.right());
            return FrontendExpressionType.deferred(
                    "Assignment expression typing is deferred until assignment semantics are implemented"
            );
        }

        private @NotNull FrontendExpressionType resolveCallExpressionType(@NotNull CallExpression callExpression) {
            publishExpressionType(callExpression.callee());
            for (var argument : callExpression.arguments()) {
                publishExpressionType(argument);
            }
            return FrontendExpressionType.deferred(
                    "Bare call expression typing is deferred until bare callable semantics are implemented"
            );
        }

        private @NotNull FrontendExpressionType resolveSubscriptExpressionType(
                @NotNull SubscriptExpression subscriptExpression
        ) {
            publishExpressionType(subscriptExpression.base());
            for (var argument : subscriptExpression.arguments()) {
                publishExpressionType(argument);
            }
            return FrontendExpressionType.deferred(
                    "Subscript expression typing is deferred until subscript semantics are implemented"
            );
        }

        private @NotNull FrontendExpressionType resolveGenericDeferredExpressionType(@NotNull Expression expression) {
            for (var child : expression.getChildren()) {
                if (child instanceof Expression childExpression) {
                    publishExpressionType(childExpression);
                }
            }
            return FrontendExpressionType.deferred(
                    "Expression type for " + expression.getClass().getSimpleName()
                            + " is deferred until milestone-D coverage reaches this node kind"
            );
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

        private boolean isNotPublished(@Nullable Node astNode) {
            return astNode == null || !scopesByAst.containsKey(astNode);
        }

        public @NotNull ClassRegistry getClassRegistry() {
            return classRegistry;
        }
    }
}
