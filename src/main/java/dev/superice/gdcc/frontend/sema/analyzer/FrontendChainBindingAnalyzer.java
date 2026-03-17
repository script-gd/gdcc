package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.frontend.sema.resolver.FrontendChainReductionHelper;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdNodePathType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.Block;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        private final @NotNull IdentityHashMap<AttributeExpression, Optional<FrontendChainReductionHelper.ReductionResult>> reducedChains =
                new IdentityHashMap<>();
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
            var cached = reducedChains.get(attributeExpression);
            if (cached != null) {
                return cached.orElse(null);
            }

            var headReceiver = resolveHeadReceiver(attributeExpression.base());
            if (headReceiver == null) {
                reducedChains.put(attributeExpression, Optional.empty());
                return null;
            }

            var result = FrontendChainReductionHelper.reduce(new FrontendChainReductionHelper.ReductionRequest(
                    attributeExpression,
                    headReceiver,
                    analysisData,
                    classRegistry,
                    this::resolveExpressionType,
                    _ -> {
                    }
            ));
            reducedChains.put(attributeExpression, Optional.of(result));
            publishReduction(result);
            return result;
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

        private @Nullable FrontendChainReductionHelper.ReceiverState resolveHeadReceiver(@NotNull Expression base) {
            return switch (base) {
                case IdentifierExpression identifierExpression -> resolveIdentifierHeadReceiver(identifierExpression);
                case SelfExpression selfExpression -> resolveSelfReceiver(selfExpression);
                case LiteralExpression literalExpression -> toResolvedLiteralReceiver(literalExpression);
                case AttributeExpression attributeExpression -> {
                    var nestedResult = reduceAttributeExpression(attributeExpression);
                    yield nestedResult != null ? nestedResult.finalReceiver() : null;
                }
                default -> receiverFromExpressionType(base, resolveExpressionType(base, false));
            };
        }

        private @Nullable FrontendChainReductionHelper.ReceiverState resolveIdentifierHeadReceiver(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var binding = analysisData.symbolBindings().get(identifierExpression);
            if (binding == null) {
                return null;
            }
            return switch (binding.kind()) {
                case TYPE_META -> resolveTypeMetaReceiver(identifierExpression);
                case SELF -> resolveSelfReceiver(identifierExpression);
                case PARAMETER, LOCAL_VAR, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON, GLOBAL_ENUM ->
                        resolveValueReceiver(identifierExpression);
                case UNKNOWN -> null;
                case LITERAL, METHOD, STATIC_METHOD, UTILITY_FUNCTION ->
                        new FrontendChainReductionHelper.ReceiverState(
                                FrontendChainReductionHelper.Status.FAILED,
                                dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                                null,
                                null,
                                "Chain head '" + identifierExpression.name() + "' does not publish a value receiver"
                        );
            };
        }

        private @NotNull FrontendChainReductionHelper.ReceiverState resolveTypeMetaReceiver(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var currentScope = scopesByAst.get(identifierExpression);
            if (currentScope == null) {
                return new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.UNSUPPORTED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        "Type-meta receiver '" + identifierExpression.name() + "' is inside a skipped subtree"
                );
            }
            var typeMetaResult = currentScope.resolveTypeMeta(identifierExpression.name(), currentRestriction);
            if (!typeMetaResult.isAllowed()) {
                return new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.FAILED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        "Published type-meta receiver '" + identifierExpression.name() + "' is no longer visible"
                );
            }
            return FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(typeMetaResult.requireValue());
        }

        private @NotNull FrontendChainReductionHelper.ReceiverState resolveValueReceiver(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var currentScope = scopesByAst.get(identifierExpression);
            if (currentScope == null) {
                return new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.UNSUPPORTED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        "Value receiver '" + identifierExpression.name() + "' is inside a skipped subtree"
                );
            }
            var valueResult = currentScope.resolveValue(identifierExpression.name(), currentRestriction);
            if (valueResult.isAllowed()) {
                return FrontendChainReductionHelper.ReceiverState.resolvedInstance(valueResult.requireValue().type());
            }
            if (valueResult.isBlocked()) {
                var winner = valueResult.requireValue();
                return FrontendChainReductionHelper.ReceiverState.blockedFrom(
                        FrontendChainReductionHelper.ReceiverState.resolvedInstance(winner.type()),
                        "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                );
            }
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.FAILED,
                    dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Published value receiver '" + identifierExpression.name() + "' is no longer visible"
            );
        }

        private @NotNull FrontendChainReductionHelper.ReceiverState resolveSelfReceiver(@NotNull Node selfNode) {
            var classScope = findEnclosingClassScope(scopesByAst.get(selfNode));
            if (classScope == null) {
                return new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.UNSUPPORTED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        "Keyword 'self' is inside a skipped subtree"
                );
            }
            var selfType = new GdObjectType(classScope.getCurrentClass().getName());
            var resolvedSelf = FrontendChainReductionHelper.ReceiverState.resolvedInstance(selfType);
            if (currentStaticContext) {
                return FrontendChainReductionHelper.ReceiverState.blockedFrom(
                        resolvedSelf,
                        "Keyword 'self' is not available in static context"
                );
            }
            return resolvedSelf;
        }

        private @NotNull FrontendChainReductionHelper.ReceiverState toResolvedLiteralReceiver(
                @NotNull LiteralExpression literalExpression
        ) {
            var literalType = resolveLiteralType(literalExpression);
            if (literalType != null) {
                return FrontendChainReductionHelper.ReceiverState.resolvedInstance(literalType);
            }
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.FAILED,
                    dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
            );
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return switch (expression) {
                case LiteralExpression literalExpression -> {
                    var literalType = resolveLiteralType(literalExpression);
                    if (literalType != null) {
                        yield FrontendChainReductionHelper.ExpressionTypeResult.resolved(literalType);
                    }
                    yield FrontendChainReductionHelper.ExpressionTypeResult.failed(
                            "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
                    );
                }
                case SelfExpression selfExpression -> expressionTypeFromReceiver(resolveSelfReceiver(selfExpression));
                case IdentifierExpression identifierExpression -> resolveIdentifierExpressionType(identifierExpression);
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                default -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                        "Expression type for " + expression.getClass().getSimpleName()
                                + " is deferred until FrontendExprTypeAnalyzer is implemented"
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
            return switch (binding.kind()) {
                case SELF -> expressionTypeFromReceiver(resolveSelfReceiver(identifierExpression));
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
                        yield FrontendChainReductionHelper.ExpressionTypeResult.failed(
                                "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                        );
                    }
                    yield FrontendChainReductionHelper.ExpressionTypeResult.failed(
                            "Published value binding '" + identifierExpression.name() + "' is no longer visible"
                    );
                }
                case TYPE_META -> FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                        "Type-meta identifier '" + identifierExpression.name()
                                + "' does not materialize a value type without an explicit static route"
                );
                case METHOD, STATIC_METHOD, UTILITY_FUNCTION -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                        "Bare callable expression '" + identifierExpression.name()
                                + "' is deferred until FrontendExprTypeAnalyzer is implemented"
                );
                case UNKNOWN, LITERAL -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        "Identifier '" + identifierExpression.name() + "' does not resolve to a typed value"
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
            return switch (result.finalReceiver().status()) {
                case RESOLVED -> FrontendChainReductionHelper.ExpressionTypeResult.resolved(
                        Objects.requireNonNull(result.finalReceiver().receiverType(), "receiverType must not be null")
                );
                case DEFERRED -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                        Objects.requireNonNull(result.finalReceiver().detailReason(), "detailReason must not be null")
                );
                case UNSUPPORTED -> FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                        Objects.requireNonNull(result.finalReceiver().detailReason(), "detailReason must not be null")
                );
                case BLOCKED, DYNAMIC, FAILED -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        Objects.requireNonNull(result.finalReceiver().detailReason(), "detailReason must not be null")
                );
            };
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult expressionTypeFromReceiver(
                @NotNull FrontendChainReductionHelper.ReceiverState receiverState
        ) {
            return switch (receiverState.status()) {
                case RESOLVED -> FrontendChainReductionHelper.ExpressionTypeResult.resolved(
                        Objects.requireNonNull(receiverState.receiverType(), "receiverType must not be null")
                );
                case DEFERRED -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
                case UNSUPPORTED -> FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
                case BLOCKED, DYNAMIC, FAILED -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
            };
        }

        private @NotNull FrontendChainReductionHelper.ReceiverState receiverFromExpressionType(
                @NotNull Expression expression,
                @NotNull FrontendChainReductionHelper.ExpressionTypeResult typeResult
        ) {
            return switch (typeResult.status()) {
                case RESOLVED -> FrontendChainReductionHelper.ReceiverState.resolvedInstance(
                        Objects.requireNonNull(typeResult.type(), "type must not be null")
                );
                case DEFERRED -> new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.DEFERRED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        Objects.requireNonNull(typeResult.detailReason(), "detailReason must not be null")
                );
                case FAILED -> new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.FAILED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        Objects.requireNonNull(typeResult.detailReason(), "detailReason must not be null")
                );
                case UNSUPPORTED -> new FrontendChainReductionHelper.ReceiverState(
                        FrontendChainReductionHelper.Status.UNSUPPORTED,
                        dev.superice.gdcc.frontend.sema.FrontendReceiverKind.UNKNOWN,
                        null,
                        null,
                        Objects.requireNonNull(typeResult.detailReason(), "detailReason must not be null")
                );
                case BLOCKED, DYNAMIC -> throw new IllegalStateException(
                        "unexpected local expression dependency status for "
                                + expression.getClass().getSimpleName() + ": " + typeResult.status()
                );
            };
        }

        private @Nullable GdType resolveLiteralType(@NotNull LiteralExpression literalExpression) {
            return switch (literalExpression.kind()) {
                case "integer" -> GdIntType.INT;
                case "float" -> GdFloatType.FLOAT;
                case "string" -> GdStringType.STRING;
                case "string_name" -> GdStringNameType.STRING_NAME;
                case "true", "false" -> GdBoolType.BOOL;
                case "null" -> GdNilType.NIL;
                case "node_path" -> GdNodePathType.NODE_PATH;
                case "number" -> literalExpression.sourceText().contains(".")
                        ? GdFloatType.FLOAT
                        : GdIntType.INT;
                default -> null;
            };
        }

        private @Nullable ClassScope findEnclosingClassScope(@Nullable Scope scope) {
            var current = scope;
            while (current != null) {
                if (current instanceof ClassScope classScope) {
                    return classScope;
                }
                current = current.getParentScope();
            }
            return null;
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
    }
}
