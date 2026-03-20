package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/// Compile-only final frontend gate that runs after the shared semantic pipeline.
///
/// The default shared `analyze(...)` path intentionally does not call this analyzer so inspection
/// and future LSP-style entrypoints can keep consuming raw frontend recovery facts. Compile-only
/// entrypoints invoke it as the final diagnostics-only barrier before lowering is allowed to start.
///
/// The gate itself stays deliberately narrow:
/// - explicit AST compile blocks for the MVP forms that lowering/backend do not support yet
/// - generic side-table scans over published `expressionTypes()` / `resolvedMembers()` /
///   `resolvedCalls()` facts that are still blocked/deferred/failed/unsupported on compile surface
/// - no new side tables and no rewrites of upstream semantic ownership
public class FrontendCompileCheckAnalyzer {
    private static final @NotNull String COMPILE_CHECK_CATEGORY = "sema.compile_check";

    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        var publishedDiagnostics = analysisData.diagnostics();
        var scopesByAst = analysisData.scopesByAst();

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerCompileCheckVisitor(
                    sourceClassRelation.unit().path(),
                    publishedDiagnostics,
                    scopesByAst,
                    analysisData.expressionTypes(),
                    analysisData.resolvedMembers(),
                    analysisData.resolvedCalls(),
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    private static @NotNull String assertCompileBlockedMessage() {
        return "assert statement is recognized by the frontend but is temporarily blocked in compile mode until "
                + "lowering/backend support lands";
    }

    private static @NotNull String expressionCompileBlockedMessage(@NotNull String expressionKind) {
        return Objects.requireNonNull(expressionKind, "expressionKind must not be null")
                + " is recognized by the frontend but is temporarily blocked in compile mode until "
                + "lowering support lands";
    }

    private static @NotNull String publishedCompileBlockedMessage(
            @NotNull String surfaceKind,
            @NotNull Enum<?> publishedStatus,
            @Nullable String detailReason
    ) {
        var message = Objects.requireNonNull(surfaceKind, "surfaceKind must not be null")
                + " remains "
                + Objects.requireNonNull(publishedStatus, "publishedStatus must not be null").name().toLowerCase(Locale.ROOT)
                + " at compile surface and is not lowering-ready in compile mode";
        if (detailReason == null || detailReason.isBlank()) {
            return message;
        }
        return message + ": " + detailReason;
    }

    private static boolean isCompileBlocking(@NotNull FrontendExpressionTypeStatus status) {
        return switch (Objects.requireNonNull(status, "status must not be null")) {
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> true;
            case RESOLVED, DYNAMIC -> false;
        };
    }

    private static boolean isCompileBlocking(@NotNull FrontendMemberResolutionStatus status) {
        return switch (Objects.requireNonNull(status, "status must not be null")) {
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> true;
            case RESOLVED, DYNAMIC -> false;
        };
    }

    private static boolean isCompileBlocking(@NotNull FrontendCallResolutionStatus status) {
        return switch (Objects.requireNonNull(status, "status must not be null")) {
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> true;
            case RESOLVED, DYNAMIC -> false;
        };
    }

    private static final class AstWalkerCompileCheckVisitor implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull DiagnosticSnapshot publishedDiagnostics;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
        private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
        private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull Set<Node> compileSurfaceNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        private final @NotNull Set<Node> handledAnchors = Collections.newSetFromMap(new IdentityHashMap<>());
        private int supportedExecutableBlockDepth;

        private AstWalkerCompileCheckVisitor(
                @NotNull Path sourcePath,
                @NotNull DiagnosticSnapshot publishedDiagnostics,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
                @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
                @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.publishedDiagnostics = Objects.requireNonNull(
                    publishedDiagnostics,
                    "publishedDiagnostics must not be null"
            );
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
            this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
            this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(Objects.requireNonNull(sourceFile, "sourceFile must not be null"));
            scanPublishedCompileBlocks();
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
            walkCallableBody(functionDeclaration, functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            if (isNotPublished(constructorDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(constructorDeclaration, constructorDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(block);
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
            markCompileSurfaceNode(expressionStatement);
            walkExpression(expressionStatement.expression());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0 || returnStatement.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(returnStatement);
            walkExpression(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportExplicitCompileBlock(assertStatement, assertCompileBlockedMessage());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedExecutableBlockDepth > 0) {
                if (variableDeclaration.kind() != DeclarationKind.VAR || variableDeclaration.value() == null) {
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                markCompileSurfaceNode(variableDeclaration);
                walkExpression(variableDeclaration.value());
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(variableDeclaration);
            walkExpression(Objects.requireNonNull(
                    variableDeclaration.value(),
                    "property initializer value must not be null"
            ));
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(ifStatement);
            walkExpression(ifStatement.condition());
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
            markCompileSurfaceNode(elifClause);
            walkExpression(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(whileStatement);
            walkExpression(whileStatement.condition());
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

        private void walkCallableBody(@NotNull Node callableOwner, @Nullable Block body) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            walkSupportedExecutableBlock(body);
        }

        private void walkStatements(@NotNull java.util.List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        private void walkNonExecutableContainerStatements(@NotNull java.util.List<Statement> statements) {
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

        private void walkExpression(@Nullable Expression expression) {
            if (expression == null) {
                return;
            }
            switch (expression) {
                case LambdaExpression _ -> {
                    // Lambdas remain outside the current compile surface and keep their upstream
                    // unsupported-subtree owner.
                }
                case ArrayExpression arrayExpression -> reportExplicitCompileBlock(
                        arrayExpression,
                        expressionCompileBlockedMessage("Array literal")
                );
                case DictionaryExpression dictionaryExpression -> reportExplicitCompileBlock(
                        dictionaryExpression,
                        expressionCompileBlockedMessage("Dictionary literal")
                );
                case PreloadExpression preloadExpression -> reportExplicitCompileBlock(
                        preloadExpression,
                        expressionCompileBlockedMessage("Preload expression")
                );
                case GetNodeExpression getNodeExpression -> reportExplicitCompileBlock(
                        getNodeExpression,
                        expressionCompileBlockedMessage("Get-node expression")
                );
                case CastExpression castExpression -> reportExplicitCompileBlock(
                        castExpression,
                        expressionCompileBlockedMessage("Cast expression")
                );
                case TypeTestExpression typeTestExpression -> reportExplicitCompileBlock(
                        typeTestExpression,
                        expressionCompileBlockedMessage("Type-test expression")
                );
                default -> {
                    markCompileSurfaceNode(expression);
                    walkNestedExpressionChildren(expression);
                }
            }
        }

        /// Some nodes such as `DictionaryExpression` wrap real expression payload under non-expression
        /// containers, so compile-surface scanning needs to recurse until it reaches nested
        /// expressions instead of assuming one child level is enough.
        private void walkNestedExpressionChildren(@NotNull Node node) {
            for (var child : node.getChildren()) {
                if (child instanceof Expression childExpression) {
                    walkExpression(childExpression);
                    continue;
                }
                markCompileSurfaceNode(child);
                walkNestedExpressionChildren(child);
            }
        }

        private void scanPublishedCompileBlocks() {
            scanExpressionTypeCompileBlocks();
            scanResolvedMemberCompileBlocks();
            scanResolvedCallCompileBlocks();
        }

        private void scanExpressionTypeCompileBlocks() {
            for (var entry : expressionTypes.entrySet()) {
                var expression = requireExpression(entry.getKey(), "expressionTypes");
                var publishedType = Objects.requireNonNull(entry.getValue(), "publishedType must not be null");
                if (!isCompileBlocking(publishedType.status()) || !compileSurfaceNodes.contains(expression)) {
                    continue;
                }
                var anchor = compileAnchorForExpression(expression);
                if (!compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                reportCompileBlock(
                        anchor,
                        publishedCompileBlockedMessage(
                                describeExpression(expression),
                                publishedType.status(),
                                publishedType.detailReason()
                        )
                );
            }
        }

        private void scanResolvedMemberCompileBlocks() {
            for (var entry : resolvedMembers.entrySet()) {
                var anchor = requireAttributePropertyStep(entry.getKey(), "resolvedMembers");
                var publishedMember = Objects.requireNonNull(entry.getValue(), "publishedMember must not be null");
                if (!isCompileBlocking(publishedMember.status()) || !compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                reportCompileBlock(
                        anchor,
                        publishedCompileBlockedMessage(
                                "Member access '" + publishedMember.memberName() + "'",
                                publishedMember.status(),
                                publishedMember.detailReason()
                        )
                );
            }
        }

        private void scanResolvedCallCompileBlocks() {
            for (var entry : resolvedCalls.entrySet()) {
                var anchor = requireAttributeCallStep(entry.getKey(), "resolvedCalls");
                var publishedCall = Objects.requireNonNull(entry.getValue(), "publishedCall must not be null");
                if (!isCompileBlocking(publishedCall.status()) || !compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                reportCompileBlock(
                        anchor,
                        publishedCompileBlockedMessage(
                                "Call step '" + publishedCall.callableName() + "'",
                                publishedCall.status(),
                                publishedCall.detailReason()
                        )
                );
            }
        }

        /// Attribute expression typing often mirrors the final member/call step fact, so compile
        /// anchoring prefers that exact step to avoid reporting both the outer expression and the
        /// terminal chain step as separate generic compile blockers.
        private @NotNull Node compileAnchorForExpression(@NotNull Expression expression) {
            if (expression instanceof AttributeExpression attributeExpression && !attributeExpression.steps().isEmpty()) {
                var finalStep = attributeExpression.steps().getLast();
                if ((finalStep instanceof AttributePropertyStep || finalStep instanceof AttributeCallStep)
                        && compileSurfaceNodes.contains(finalStep)) {
                    return finalStep;
                }
            }
            return expression;
        }

        private static @NotNull Expression requireExpression(@NotNull Node node, @NotNull String sideTableName) {
            if (node instanceof Expression expression) {
                return expression;
            }
            throw new IllegalStateException(sideTableName + " must be keyed by expression nodes");
        }

        private static @NotNull AttributePropertyStep requireAttributePropertyStep(
                @NotNull Node node,
                @NotNull String sideTableName
        ) {
            if (node instanceof AttributePropertyStep attributePropertyStep) {
                return attributePropertyStep;
            }
            throw new IllegalStateException(sideTableName + " must be keyed by attribute property steps");
        }

        private static @NotNull AttributeCallStep requireAttributeCallStep(
                @NotNull Node node,
                @NotNull String sideTableName
        ) {
            if (node instanceof AttributeCallStep attributeCallStep) {
                return attributeCallStep;
            }
            throw new IllegalStateException(sideTableName + " must be keyed by attribute call steps");
        }

        private static @NotNull String describeExpression(@NotNull Expression expression) {
            return switch (Objects.requireNonNull(expression, "expression must not be null")) {
                case AttributeExpression _ -> "Attribute expression";
                default -> "Expression";
            };
        }

        private void reportExplicitCompileBlock(@NotNull Node anchor, @NotNull String message) {
            markCompileSurfaceNode(anchor);
            reportCompileBlock(anchor, message);
        }

        private void reportCompileBlock(@NotNull Node anchor, @NotNull String message) {
            Objects.requireNonNull(anchor, "anchor must not be null");
            Objects.requireNonNull(message, "message must not be null");
            if (!handledAnchors.add(anchor)) {
                return;
            }
            if (hasPublishedErrorAt(anchor)) {
                return;
            }
            diagnosticManager.error(
                    COMPILE_CHECK_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(anchor.range())
            );
        }

        private boolean hasPublishedErrorAt(@NotNull Node anchor) {
            var anchorRange = FrontendRange.fromAstRange(anchor.range());
            return publishedDiagnostics.asList().stream().anyMatch(diagnostic ->
                    diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                            && Objects.equals(diagnostic.sourcePath(), sourcePath)
                            && Objects.equals(diagnostic.range(), anchorRange)
            );
        }

        private void markCompileSurfaceNode(@NotNull Node node) {
            compileSurfaceNodes.add(Objects.requireNonNull(node, "node must not be null"));
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }
}
