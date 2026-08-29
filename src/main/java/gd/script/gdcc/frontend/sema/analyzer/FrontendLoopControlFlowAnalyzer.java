package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Diagnostics-only analyzer for source-level `break` / `continue` legality.
///
/// The analyzer intentionally runs in the shared semantic pipeline instead of the compile-only
/// gate because loop-control placement is a source contract, not a lowering readiness fact.
/// Current behavior is kept deliberately narrow:
/// - report `sema.loop_control_flow` when `break` / `continue` appears outside the current loop
///   boundary
/// - treat `while` / `for` as loop boundaries
/// - reset loop depth at function / constructor / lambda boundaries so outer loops do not leak
///   into nested callables
/// - keep missing-scope subtrees silent so earlier recovery ownership remains intact
public class FrontendLoopControlFlowAnalyzer {
    private static final @NotNull String LOOP_CONTROL_FLOW_CATEGORY = "sema.loop_control_flow";

    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
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

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerLoopControlFlowVisitor(
                    sourceClassRelation.unit().path(),
                    scopesByAst,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    private static @NotNull String breakOutsideLoopMessage() {
        return "`break` statement must be enclosed by `while` or `for` within the current callable boundary";
    }

    private static @NotNull String continueOutsideLoopMessage() {
        return "`continue` statement must be enclosed by `while` or `for` within the current callable boundary";
    }

    private static final class AstWalkerLoopControlFlowVisitor implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        /// Tracks how many loop boundaries currently surround the active statement walk.
        ///
        /// This is intentionally scoped to the current callable boundary:
        /// - entering `while` / `for` increments it
        /// - entering function / constructor / lambda bodies resets it to `0`
        /// - ordinary blocks and branch bodies inherit the surrounding loop depth unchanged
        private int loopDepth;

        private AstWalkerLoopControlFlowVisitor(
                @NotNull Path sourcePath,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(Objects.requireNonNull(sourceFile, "sourceFile must not be null"));
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkStatements(sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(classDeclaration.body().statements());
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
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            if (isNotPublished(lambdaExpression)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(lambdaExpression, lambdaExpression.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            scanNestedCallableBoundaries(variableDeclaration.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleExpressionStatement(
                @NotNull ExpressionStatement expressionStatement
        ) {
            scanNestedCallableBoundaries(expressionStatement.expression());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            scanNestedCallableBoundaries(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Both the condition and the optional message can host lambdas with loop-control
        /// statements, so both must be scanned for nested callable boundaries.
        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            scanNestedCallableBoundaries(assertStatement.condition());
            scanNestedCallableBoundaries(assertStatement.message());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            scanNestedCallableBoundaries(ifStatement.condition());
            astWalker.walk(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                astWalker.walk(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            if (isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            scanNestedCallableBoundaries(elifClause.condition());
            astWalker.walk(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            scanNestedCallableBoundaries(whileStatement.condition());
            walkLoopBody(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (isNotPublished(forStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            scanNestedCallableBoundaries(forStatement.iterable());
            walkLoopBody(forStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (isNotPublished(matchStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            scanNestedCallableBoundaries(matchStatement.value());
            for (var section : matchStatement.sections()) {
                astWalker.walk(section);
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchSection(@NotNull MatchSection matchSection) {
            if (isNotPublished(matchSection)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            for (var pattern : matchSection.patterns()) {
                scanNestedCallableBoundaries(pattern);
            }
            scanNestedCallableBoundaries(matchSection.guard());
            astWalker.walk(matchSection.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBreakStatement(@NotNull BreakStatement breakStatement) {
            if (loopDepth <= 0) {
                reportLoopControlError(breakStatement, breakOutsideLoopMessage());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleContinueStatement(@NotNull ContinueStatement continueStatement) {
            if (loopDepth <= 0) {
                reportLoopControlError(continueStatement, continueOutsideLoopMessage());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void walkCallableBody(@NotNull Node callableOwner, @Nullable Block body) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            var previousLoopDepth = loopDepth;
            loopDepth = 0;
            try {
                astWalker.walk(body);
            } finally {
                loopDepth = previousLoopDepth;
            }
        }

        private void walkLoopBody(@Nullable Block body) {
            if (isNotPublished(body)) {
                return;
            }
            loopDepth++;
            try {
                astWalker.walk(body);
            } finally {
                loopDepth--;
            }
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        /// Only nested callable boundaries matter inside expressions because `break` / `continue`
        /// themselves are statements. The recursive scan therefore ignores ordinary expression
        /// semantics and re-enters the main walker only when it finds a lambda body.
        private void scanNestedCallableBoundaries(@Nullable Node node) {
            if (node == null) {
                return;
            }
            if (node instanceof LambdaExpression lambdaExpression) {
                astWalker.walk(lambdaExpression);
                return;
            }
            for (var child : node.getChildren()) {
                if (child instanceof LambdaExpression lambdaExpression) {
                    astWalker.walk(lambdaExpression);
                    continue;
                }
                scanNestedCallableBoundaries(child);
            }
        }

        private void reportLoopControlError(
                @NotNull Node node,
                @NotNull String message
        ) {
            diagnosticManager.error(
                    LOOP_CONTROL_FLOW_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(node.range())
            );
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }
}
