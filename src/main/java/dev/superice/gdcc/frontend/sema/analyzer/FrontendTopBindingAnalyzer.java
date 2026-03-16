package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
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

/// Frontend top-binding phase skeleton.
///
/// Stage B1 freezes only the phase boundary and traversal shape:
/// - require skeleton, diagnostics, and published top-level source scopes
/// - rebuild `symbolBindings()` from scratch on every run
/// - traverse only the currently supported executable subtrees
/// - keep parameter-default / lambda / `for` / `match` binding deferred
///
/// Actual symbol-category resolution is intentionally deferred to later stages. The skeleton
/// therefore publishes an empty binding table today while locking the AST-walk contract that later
/// binding work will fill in.
public class FrontendTopBindingAnalyzer {
    /// Runs the top-binding phase against the shared analysis carrier.
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

        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerTopBindingSkeleton(scopesByAst).walk(sourceClassRelation.unit().ast());
        }
        analysisData.updateSymbolBindings(symbolBindings);
    }

    private enum ExpressionPosition {
        VALUE,
        BARE_CALLEE,
        TOP_LEVEL_TYPE_META_CANDIDATE
    }

    /// `ASTWalker` remains the typed dispatch engine, while this handler explicitly controls
    /// which executable subtrees are accepted by the current MVP skeleton.
    private static final class AstWalkerTopBindingSkeleton implements ASTNodeHandler {
        private final @NotNull FrontendAstSideTable<?> scopesByAst;
        private final @NotNull ASTWalker astWalker;
        private int supportedExecutableBlockDepth;

        private AstWalkerTopBindingSkeleton(@NotNull FrontendAstSideTable<?> scopesByAst) {
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
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
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            walkCallableBody(functionDeclaration, functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            walkCallableBody(constructorDeclaration, constructorDeclaration.body());
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
            if (supportedExecutableBlockDepth <= 0
                    || variableDeclaration.kind() != DeclarationKind.VAR
                    || variableDeclaration.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(variableDeclaration.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
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
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(whileStatement.condition());
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(matchStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(matchStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void walkCallableBody(@NotNull Node callableOwner, @Nullable Block body) {
            if (isNotPublished(callableOwner)) {
                return;
            }
            walkSupportedExecutableBlock(body);
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

        private void walkValueExpression(@NotNull Expression expression) {
            walkExpression(expression, ExpressionPosition.VALUE);
        }

        private void walkExpression(
                @Nullable Expression expression,
                @NotNull ExpressionPosition position
        ) {
            if (expression == null) {
                return;
            }
            switch (expression) {
                case IdentifierExpression identifierExpression -> visitIdentifier(identifierExpression, position);
                case SelfExpression selfExpression -> visitSelf(selfExpression);
                case LiteralExpression literalExpression -> visitLiteral(literalExpression);
                case AttributeExpression attributeExpression -> walkAttributeExpression(attributeExpression);
                case SubscriptExpression subscriptExpression -> walkSubscriptExpression(subscriptExpression);
                case CallExpression callExpression -> walkCallExpression(callExpression);
                case LambdaExpression lambdaExpression -> {
                    // Lambda binding remains deferred in the current MVP. Keeping the subtree sealed
                    // here ensures later stages must opt in explicitly instead of inheriting it by
                    // accident from a generic expression walk.
                }
                default -> walkGenericExpressionChildren(expression);
            }
        }

        private void walkAttributeExpression(@NotNull AttributeExpression attributeExpression) {
            // Current MVP only binds the outermost chain head. Tail property/call steps remain for
            // later member/call phases, so the walker intentionally descends into the base only.
            walkChainHeadBaseExpression(attributeExpression.base());
        }

        private void walkSubscriptExpression(@NotNull SubscriptExpression subscriptExpression) {
            walkValueExpression(subscriptExpression.base());
            for (var argument : subscriptExpression.arguments()) {
                walkValueExpression(argument);
            }
        }

        private void walkCallExpression(@NotNull CallExpression callExpression) {
            switch (callExpression.callee()) {
                case IdentifierExpression identifierExpression ->
                        visitIdentifier(identifierExpression, ExpressionPosition.BARE_CALLEE);
                case AttributeExpression attributeExpression ->
                        walkChainHeadBaseExpression(attributeExpression.base());
                default -> walkValueExpression(callExpression.callee());
            }
            for (var argument : callExpression.arguments()) {
                walkValueExpression(argument);
            }
        }

        private void walkChainHeadBaseExpression(@NotNull Expression expression) {
            switch (expression) {
                case IdentifierExpression identifierExpression ->
                        visitIdentifier(identifierExpression, ExpressionPosition.TOP_LEVEL_TYPE_META_CANDIDATE);
                case SelfExpression selfExpression -> visitSelf(selfExpression);
                case LiteralExpression literalExpression -> visitLiteral(literalExpression);
                case AttributeExpression attributeExpression -> walkChainHeadBaseExpression(attributeExpression.base());
                default -> walkValueExpression(expression);
            }
        }

        private void walkGenericExpressionChildren(@NotNull Expression expression) {
            for (var child : expression.getChildren()) {
                if (child instanceof Expression childExpression) {
                    walkValueExpression(childExpression);
                }
            }
        }

        private void visitIdentifier(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull ExpressionPosition position
        ) {
            // Stage B1 intentionally publishes no binding facts yet. The future B2/B3
            // implementation will resolve `identifierExpression` according to `position`.
            Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
            Objects.requireNonNull(position, "position must not be null");
        }

        private void visitSelf(@NotNull SelfExpression selfExpression) {
            Objects.requireNonNull(selfExpression, "selfExpression must not be null");
        }

        private void visitLiteral(@NotNull LiteralExpression literalExpression) {
            Objects.requireNonNull(literalExpression, "literalExpression must not be null");
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }
}
