package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendAssignmentSemanticSupport;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
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
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Publishes body-phase chain member/call results from already-published binding and scope facts.
///
/// The analyzer keeps ordinary expression publication in `FrontendExprTypeAnalyzer`.
/// It only performs the local dependency typing needed to keep one chain reducing left-to-right.
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
        private final @NotNull FrontendAssignmentSemanticSupport assignmentSemanticSupport;
        private final @NotNull FrontendExpressionSemanticSupport expressionSemanticSupport;
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;
        private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext;

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
                    () -> currentPropertyInitializerContext,
                    classRegistry,
                    this::resolveExpressionType
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
            if (supportedExecutableBlockDepth > 0) {
                if (variableDeclaration.value() == null) {
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                if (variableDeclaration.kind() == DeclarationKind.CONST) {
                    reportDeferredSubtree(
                            variableDeclaration.value(),
                            FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE
                    );
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                if (variableDeclaration.kind() != DeclarationKind.VAR) {
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                walkValueExpression(variableDeclaration.value());
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkPropertyInitializer(variableDeclaration);
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

        /// Property initializers share chain/member/call publication with executable expressions,
        /// but they must keep class-body traversal sealed everywhere except the initializer subtree.
        private void walkPropertyInitializer(@NotNull VariableDeclaration variableDeclaration) {
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
                walkValueExpression(initializer);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentRestriction = previousRestriction;
                currentStaticContext = previousStaticContext;
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
                if (shouldSuppressPropertyInitializerHeadBoundary(recoveryRoot)) {
                    return;
                }
                if (reportedUnsupportedRoots.putIfAbsent(recoveryRoot, Boolean.TRUE) != null) {
                    return;
                }
                diagnosticManager.error(
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
            return switch (expression) {
                case LiteralExpression literalExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveLiteralExpressionType(literalExpression)
                );
                case SelfExpression selfExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveSelfExpressionType(selfExpression)
                );
                case IdentifierExpression identifierExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveIdentifierExpressionType(identifierExpression)
                );
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                case CallExpression callExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveCallExpressionType(
                                callExpression,
                                this::resolveExpressionDependencyType,
                                false,
                                finalizeWindow
                        )
                );
                case AssignmentExpression assignmentExpression -> bridgeExpressionResolution(
                        assignmentSemanticSupport.resolveAssignmentExpressionType(
                                assignmentExpression,
                                FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                                this::resolveExpressionDependencyType,
                                finalizeWindow
                        )
                );
                case SubscriptExpression subscriptExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveSubscriptExpressionType(
                                subscriptExpression,
                                this::resolveExpressionDependencyType,
                                finalizeWindow
                        )
                );
                case LambdaExpression lambdaExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveLambdaExpressionType(
                                lambdaExpression,
                                this::resolveExpressionDependencyType,
                                false,
                                finalizeWindow
                        )
                );
                case UnaryExpression unaryExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveUnaryExpressionType(
                                unaryExpression,
                                this::resolveExpressionDependencyType,
                                finalizeWindow
                        )
                );
                case BinaryExpression binaryExpression -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveBinaryExpressionType(
                                binaryExpression,
                                this::resolveExpressionDependencyType,
                                finalizeWindow
                        )
                );
                default -> bridgeExpressionResolution(
                        expressionSemanticSupport.resolveRemainingExplicitExpressionType(
                                expression,
                                this::resolveExpressionDependencyType,
                                false,
                                finalizeWindow
                        )
                );
            };
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
            if (domain == FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE) {
                if (reportedDeferredRoots.putIfAbsent(subtreeRoot, Boolean.TRUE) != null) {
                    return;
                }
                diagnosticManager.warning(
                        DEFERRED_CHAIN_RESOLUTION_CATEGORY,
                        "Chain binding analysis is skipped in " + formatDomain(domain),
                        sourcePath,
                        FrontendRange.fromAstRange(subtreeRoot.range())
                );
                return;
            }
            if (reportedUnsupportedRoots.putIfAbsent(subtreeRoot, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.error(
                    UNSUPPORTED_CHAIN_ROUTE_CATEGORY,
                    "Chain binding analysis is not supported in " + formatDomain(domain),
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

        private boolean shouldSuppressPropertyInitializerHeadBoundary(@Nullable Node recoveryRoot) {
            return FrontendPropertyInitializerSupport.isTopBindingOwnedUnsupportedHead(
                    currentPropertyInitializerContext,
                    recoveryRoot
            );
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult bridgeExpressionResolution(
                @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult resolution
        ) {
            return FrontendChainStatusBridge.toExpressionTypeResult(resolution.expressionType());
        }


        private @NotNull FrontendExpressionType resolveExpressionDependencyType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return FrontendChainStatusBridge.toPublishedExpressionType(resolveExpressionType(expression, finalizeWindow));
        }

        public @NotNull ClassRegistry getClassRegistry() {
            return classRegistry;
        }

        public @NotNull FrontendAnalysisData getAnalysisData() {
            return analysisData;
        }
    }
}
