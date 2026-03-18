package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
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
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Publishes body-phase chain member/call results from already-published binding and scope facts.
///
/// The analyzer deliberately keeps expression typing out of the published surface for milestone C.
/// It only performs a small local type callback so one chain can continue reducing left-to-right.
public class FrontendChainBindingAnalyzer {
    private static final @NotNull String MEMBER_RESOLUTION_CATEGORY = "sema.member_resolution";
    private static final @NotNull String CALL_RESOLUTION_CATEGORY = "sema.call_resolution";
    private static final @NotNull String DEFERRED_CHAIN_RESOLUTION_CATEGORY = "sema.deferred_chain_resolution";
    private static final @NotNull String UNSUPPORTED_CHAIN_ROUTE_CATEGORY = "sema.unsupported_chain_route";

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

        var resolvedMembers = new FrontendAstSideTable<FrontendResolvedMember>();
        var resolvedCalls = new FrontendAstSideTable<FrontendResolvedCall>();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerChainBinder(
                    sourceClassRelation.unit().path(),
                    classRegistry,
                    analysisData,
                    scopesByAst,
                    resolvedMembers,
                    resolvedCalls,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
        analysisData.updateResolvedMembers(resolvedMembers);
        analysisData.updateResolvedCalls(resolvedCalls);
    }

    private static final class AstWalkerChainBinder implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull ClassRegistry classRegistry;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
        private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull IdentityHashMap<Node, Boolean> reportedDeferredRoots = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, Boolean> reportedUnsupportedRoots = new IdentityHashMap<>();
        private final @NotNull FrontendChainReductionFacade chainReduction;
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;

        private AstWalkerChainBinder(
                @NotNull Path sourcePath,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
                @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
            this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
            chainReduction = new FrontendChainReductionFacade(
                    analysisData,
                    scopesByAst,
                    () -> currentRestriction,
                    () -> currentStaticContext,
                    classRegistry,
                    this::resolveExpressionType
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
                reportDeferredSubtree(classDeclaration, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
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
                reportDeferredSubtree(functionDeclaration, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportDeferredParameterDefaults(functionDeclaration.parameters());
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
                reportDeferredSubtree(constructorDeclaration, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportDeferredParameterDefaults(constructorDeclaration.parameters());
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
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(block)) {
                reportDeferredSubtree(block, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
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
            walkValueExpression(expressionStatement.expression());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0 || returnStatement.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(assertStatement.condition());
            if (assertStatement.message() != null) {
                walkValueExpression(assertStatement.message());
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
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                reportDeferredSubtree(variableDeclaration.value(), FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() != DeclarationKind.VAR) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(variableDeclaration.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(ifStatement)) {
                reportDeferredSubtree(ifStatement, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(ifStatement.condition());
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
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(elifClause)) {
                reportDeferredSubtree(elifClause, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(whileStatement)) {
                reportDeferredSubtree(whileStatement, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(whileStatement.condition());
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(forStatement)) {
                reportDeferredSubtree(forStatement, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportDeferredSubtree(forStatement, FrontendVisibleValueDomain.FOR_SUBTREE);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(matchStatement)) {
                reportDeferredSubtree(matchStatement, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(matchStatement.value());
            if (!matchStatement.sections().isEmpty()) {
                reportDeferredSubtree(matchStatement, FrontendVisibleValueDomain.MATCH_SUBTREE);
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
            if (isNotPublished(callableOwner)) {
                reportDeferredSubtree(callableOwner, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return;
            }
            if (isNotPublished(body)) {
                reportDeferredSubtree(body, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
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
                reportDeferredSubtree(block, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
                return;
            }
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private void walkValueExpression(@Nullable Expression expression) {
            if (expression == null) {
                return;
            }
            switch (expression) {
                case AttributeExpression attributeExpression -> reduceAttributeExpression(attributeExpression);
                case LambdaExpression lambdaExpression ->
                        reportDeferredSubtree(lambdaExpression, FrontendVisibleValueDomain.LAMBDA_SUBTREE);
                default -> walkGenericExpressionChildren(expression);
            }
        }

        private void walkGenericExpressionChildren(@NotNull Node node) {
            for (var child : node.getChildren()) {
                if (child instanceof Expression childExpression) {
                    walkValueExpression(childExpression);
                    continue;
                }
                walkGenericExpressionChildren(child);
            }
        }

        /// Attribute-expression reduction is cached so argument typing can request nested chains on
        /// demand without double-publishing diagnostics or side-table entries.
        private @Nullable FrontendChainReductionHelper.ReductionResult reduceAttributeExpression(
                @NotNull AttributeExpression attributeExpression
        ) {
            var reduced = chainReduction.reduce(attributeExpression);
            if (reduced.computedNow() && reduced.result() != null) {
                publishReduction(reduced.result());
            }
            return reduced.result();
        }

        private void publishReduction(@NotNull FrontendChainReductionHelper.ReductionResult result) {
            for (var trace : result.stepTraces()) {
                if (trace.suggestedMember() != null) {
                    resolvedMembers.put(trace.step(), trace.suggestedMember());
                    reportMemberTrace(trace);
                }
                if (trace.suggestedCall() != null) {
                    resolvedCalls.put(trace.step(), trace.suggestedCall());
                    reportCallTrace(trace);
                }
            }
            reportRecoveryBoundary(result);
            for (var note : result.notes()) {
                diagnosticManager.warning(
                        CALL_RESOLUTION_CATEGORY,
                        note.message(),
                        sourcePath,
                        FrontendRange.fromAstRange(note.anchor().range())
                );
            }
        }

        private void reportMemberTrace(@NotNull FrontendChainReductionHelper.StepTrace trace) {
            if (trace.status() != FrontendChainReductionHelper.Status.BLOCKED
                    && trace.status() != FrontendChainReductionHelper.Status.FAILED) {
                return;
            }
            diagnosticManager.error(
                    MEMBER_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(trace.detailReason(), "detailReason must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(trace.step().range())
            );
        }

        private void reportCallTrace(@NotNull FrontendChainReductionHelper.StepTrace trace) {
            if (trace.status() != FrontendChainReductionHelper.Status.BLOCKED
                    && trace.status() != FrontendChainReductionHelper.Status.FAILED) {
                return;
            }
            diagnosticManager.error(
                    CALL_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(trace.detailReason(), "detailReason must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(trace.step().range())
            );
        }

        private void reportRecoveryBoundary(@NotNull FrontendChainReductionHelper.ReductionResult result) {
            var recoveryRoot = result.recoveryRoot();
            if (recoveryRoot == null || result.stepTraces().isEmpty()) {
                return;
            }
            var firstNonResolved = result.stepTraces().stream()
                    .filter(trace -> trace.status() != FrontendChainReductionHelper.Status.RESOLVED)
                    .findFirst()
                    .orElse(null);
            if (firstNonResolved == null) {
                return;
            }
            if (firstNonResolved.status() == FrontendChainReductionHelper.Status.DEFERRED) {
                if (reportedDeferredRoots.putIfAbsent(recoveryRoot, Boolean.TRUE) != null) {
                    return;
                }
                diagnosticManager.warning(
                        DEFERRED_CHAIN_RESOLUTION_CATEGORY,
                        Objects.requireNonNull(firstNonResolved.detailReason(), "detailReason must not be null"),
                        sourcePath,
                        FrontendRange.fromAstRange(recoveryRoot.range())
                );
                return;
            }
            if (firstNonResolved.status() == FrontendChainReductionHelper.Status.UNSUPPORTED) {
                if (reportedUnsupportedRoots.putIfAbsent(recoveryRoot, Boolean.TRUE) != null) {
                    return;
                }
                diagnosticManager.warning(
                        UNSUPPORTED_CHAIN_ROUTE_CATEGORY,
                        Objects.requireNonNull(firstNonResolved.detailReason(), "detailReason must not be null"),
                        sourcePath,
                        FrontendRange.fromAstRange(recoveryRoot.range())
                );
            }
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            var support = chainReduction.headReceiverSupport();
            return switch (expression) {
                case LiteralExpression literalExpression -> {
                    var literalType = support.resolveLiteralType(literalExpression);
                    if (literalType != null) {
                        yield FrontendChainReductionHelper.ExpressionTypeResult.resolved(literalType);
                    }
                    yield FrontendChainReductionHelper.ExpressionTypeResult.failed(
                            "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
                    );
                }
                case SelfExpression selfExpression ->
                        FrontendChainStatusBridge.toExpressionTypeResult(support.resolveSelfReceiver(selfExpression));
                case IdentifierExpression identifierExpression -> resolveIdentifierExpressionType(identifierExpression);
                case CallExpression callExpression -> resolveCallExpressionType(callExpression, finalizeWindow);
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                default -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                        "Expression type for " + expression.getClass().getSimpleName()
                                + " is deferred until local chain dependency typing covers this node kind"
                );
            };
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveIdentifierExpressionType(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var binding = analysisData.symbolBindings().get(identifierExpression);
            if (binding == null) {
                return FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        "No published binding fact is available for identifier '" + identifierExpression.name() + "'"
                );
            }
            var support = chainReduction.headReceiverSupport();
            return switch (binding.kind()) {
                case SELF ->
                        FrontendChainStatusBridge.toExpressionTypeResult(support.resolveSelfReceiver(identifierExpression));
                case PARAMETER, LOCAL_VAR, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON, GLOBAL_ENUM -> {
                    var currentScope = scopesByAst.get(identifierExpression);
                    if (currentScope == null) {
                        yield FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                                "Identifier '" + identifierExpression.name() + "' is inside a skipped subtree"
                        );
                    }
                    var valueResult = currentScope.resolveValue(identifierExpression.name(), currentRestriction);
                    if (valueResult.isAllowed()) {
                        yield FrontendChainReductionHelper.ExpressionTypeResult.resolved(valueResult.requireValue().type());
                    }
                    if (valueResult.isBlocked()) {
                        var winner = valueResult.requireValue();
                        yield FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                                winner.type(),
                                "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                        );
                    }
                    yield FrontendChainReductionHelper.ExpressionTypeResult.failed(
                            "Published value binding '" + identifierExpression.name() + "' is no longer visible"
                    );
                }
                case TYPE_META -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        "Type-meta identifier '" + identifierExpression.name()
                                + "' cannot be consumed as an ordinary value without an explicit static route"
                );
                case METHOD, STATIC_METHOD, UTILITY_FUNCTION ->
                        resolveCallableIdentifierExpressionType(identifierExpression);
                case UNKNOWN, LITERAL -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        "Identifier '" + identifierExpression.name() + "' does not resolve to a typed value"
                );
            };
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveCallableIdentifierExpressionType(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var currentScope = scopesByAst.get(identifierExpression);
            if (currentScope == null) {
                return FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                        "Callable expression '" + identifierExpression.name() + "' is inside a skipped subtree"
                );
            }
            var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction);
            var callableType = new GdCallableType();
            if (functionResult.isAllowed()) {
                return FrontendChainReductionHelper.ExpressionTypeResult.resolved(callableType);
            }
            if (functionResult.isBlocked()) {
                return FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                        callableType,
                        "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                );
            }
            return FrontendChainReductionHelper.ExpressionTypeResult.failed(
                    "Published callable binding '" + identifierExpression.name() + "' is no longer visible"
            );
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveAttributeExpressionType(
                @NotNull AttributeExpression attributeExpression
        ) {
            var result = reduceAttributeExpression(attributeExpression);
            if (result == null) {
                return FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                        "Nested chain expression is inside an unsupported or skipped subtree"
                );
            }
            return FrontendChainStatusBridge.toExpressionTypeResult(result);
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveCallExpressionType(
                @NotNull CallExpression callExpression,
                boolean finalizeWindow
        ) {
            if (callExpression.callee() instanceof IdentifierExpression bareCallee) {
                var calleeType = resolveExpressionType(bareCallee, finalizeWindow);
                if (calleeType.status() != FrontendChainReductionHelper.Status.RESOLVED) {
                    return calleeType;
                }
                var argumentResolution = resolveCallArgumentTypes(
                        callExpression.arguments(),
                        finalizeWindow
                );
                if (argumentResolution.issue() != null) {
                    return argumentResolution.issue();
                }
                return resolveBareIdentifierCallExpression(
                        bareCallee,
                        argumentResolution.argumentTypes()
                );
            }

            var calleeType = resolveExpressionType(callExpression.callee(), finalizeWindow);
            if (calleeType.status() != FrontendChainReductionHelper.Status.RESOLVED) {
                return calleeType;
            }
            var argumentResolution = resolveCallArgumentTypes(
                    callExpression.arguments(),
                    finalizeWindow
            );
            if (argumentResolution.issue() != null) {
                return argumentResolution.issue();
            }
            return calleeType.type() instanceof GdCallableType
                    ? FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                    "Direct invocation of callable values is not implemented yet unless the callee is a bare identifier"
            )
                    : FrontendChainReductionHelper.ExpressionTypeResult.failed(
                    "Call target does not resolve to a callable value"
            );
        }

        private @NotNull CallArgumentResolution resolveCallArgumentTypes(
                @NotNull List<? extends Expression> arguments,
                boolean finalizeWindow
        ) {
            var argumentTypes = new ArrayList<GdType>(arguments.size());
            for (var argument : arguments) {
                var argumentType = resolveExpressionType(argument, finalizeWindow);
                switch (argumentType.status()) {
                    case RESOLVED, DYNAMIC -> argumentTypes.add(argumentType.type());
                    case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> {
                        return new CallArgumentResolution(List.of(), argumentType);
                    }
                }
            }
            return new CallArgumentResolution(List.copyOf(argumentTypes), null);
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveBareIdentifierCallExpression(
                @NotNull IdentifierExpression bareCallee,
                @NotNull List<GdType> argumentTypes
        ) {
            var currentScope = scopesByAst.get(bareCallee);
            if (currentScope == null) {
                return FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                        "Bare call '" + bareCallee.name() + "(...)' is inside a skipped subtree"
                );
            }
            var functionResult = currentScope.resolveFunctions(bareCallee.name(), currentRestriction);
            if (functionResult.isAllowed()) {
                var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
                if (overloadSelection.selected() != null) {
                    return FrontendChainReductionHelper.ExpressionTypeResult.resolved(
                            overloadSelection.selected().getReturnType()
                    );
                }
                return FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        Objects.requireNonNull(overloadSelection.detailReason(), "detailReason must not be null")
                );
            }
            if (functionResult.isBlocked()) {
                var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
                var blockedReturnType = overloadSelection.selected() != null
                        ? overloadSelection.selected().getReturnType()
                        : null;
                return FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                        blockedReturnType,
                        "Binding '" + bareCallee.name() + "' is not accessible in the current context"
                );
            }
            return FrontendChainReductionHelper.ExpressionTypeResult.failed(
                    "Published bare callee binding '" + bareCallee.name() + "' is no longer visible"
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

        private void reportDeferredParameterDefaults(@NotNull List<Parameter> parameters) {
            for (var parameter : parameters) {
                if (parameter.defaultValue() != null) {
                    reportDeferredSubtree(parameter.defaultValue(), FrontendVisibleValueDomain.PARAMETER_DEFAULT);
                }
            }
        }

        private void reportDeferredSubtree(
                @Nullable Node subtreeRoot,
                @NotNull FrontendVisibleValueDomain domain
        ) {
            if (subtreeRoot == null) {
                return;
            }
            if (reportedDeferredRoots.putIfAbsent(subtreeRoot, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.warning(
                    DEFERRED_CHAIN_RESOLUTION_CATEGORY,
                    "Chain binding analysis is deferred in " + formatDomain(domain),
                    sourcePath,
                    FrontendRange.fromAstRange(subtreeRoot.range())
            );
        }

        private @NotNull String formatDomain(@NotNull FrontendVisibleValueDomain domain) {
            return switch (Objects.requireNonNull(domain, "domain must not be null")) {
                case EXECUTABLE_BODY -> "executable body";
                case PARAMETER_DEFAULT -> "parameter default";
                case LAMBDA_SUBTREE -> "lambda subtree";
                case BLOCK_LOCAL_CONST_SUBTREE -> "block-local const initializer";
                case FOR_SUBTREE -> "for subtree";
                case MATCH_SUBTREE -> "match subtree";
                case UNKNOWN_OR_SKIPPED_SUBTREE -> "skipped subtree";
            };
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }

        private record CallArgumentResolution(
                @NotNull List<GdType> argumentTypes,
                @Nullable FrontendChainReductionHelper.ExpressionTypeResult issue
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
