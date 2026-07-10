package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Root-bounded statement dispatcher for the new body SuiteResolver skeleton.
///
/// The no-op constructor is retained for traversal tests and explicit legacy shims. Production
/// wiring reaches this dispatcher through `FrontendSuiteResolver`, which supplies real owner
/// procedures and publishes facts through the typed lexical environment.
public class FrontendStatementResolver {
    private final @NotNull OwnerProcedures ownerProcedures;

    public FrontendStatementResolver() {
        this(OwnerProcedures.noop());
    }

    public FrontendStatementResolver(@NotNull OwnerProcedures ownerProcedures) {
        this.ownerProcedures = Objects.requireNonNull(ownerProcedures, "ownerProcedures must not be null");
    }

    public void resolvePropertyInitializer(
            @NotNull FrontendSuiteContext context,
            @NotNull VariableDeclaration propertyInitializer
    ) {
        resolveSupportedRoot(context, propertyInitializer);
    }

    public void resolveStatement(
            @NotNull FrontendSuiteContext context,
            @NotNull Statement statement,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(childSuiteResolver, "childSuiteResolver must not be null");

        switch (statement) {
            case VariableDeclaration variableDeclaration -> resolveVariableDeclaration(context, variableDeclaration);
            case ExpressionStatement expressionStatement -> resolveSupportedRoot(context, expressionStatement);
            case ReturnStatement returnStatement -> resolveSupportedRoot(context, returnStatement);
            case AssertStatement assertStatement -> resolveSupportedRoot(context, assertStatement);
            case IfStatement ifStatement -> resolveIfStatement(context, ifStatement, childSuiteResolver);
            case WhileStatement whileStatement -> resolveWhileStatement(context, whileStatement, childSuiteResolver);
            case ForStatement forStatement -> resolveUnsupportedRoot(context, forStatement);
            case MatchStatement matchStatement -> resolveUnsupportedRoot(context, matchStatement);
            case PassStatement _, BreakStatement _, ContinueStatement _ -> flushStatementBoundary(context);
            default -> resolveUnsupportedRoot(context, statement);
        }
    }

    private void resolveVariableDeclaration(
            @NotNull FrontendSuiteContext context,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        if (variableDeclaration.kind() == DeclarationKind.VAR) {
            resolveSupportedRoot(context, variableDeclaration);
            return;
        }
        resolveUnsupportedRoot(context, variableDeclaration);
    }

    private void resolveIfStatement(
            @NotNull FrontendSuiteContext context,
            @NotNull IfStatement ifStatement,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        resolveSupportedRoot(context, ifStatement.condition());
        childSuiteResolver.resolveChildSuite(context, ifStatement.body());
        for (var elifClause : ifStatement.elifClauses()) {
            resolveElifClause(context, elifClause, childSuiteResolver);
        }
        if (ifStatement.elseBody() != null) {
            childSuiteResolver.resolveChildSuite(context, ifStatement.elseBody());
        }
    }

    private void resolveElifClause(
            @NotNull FrontendSuiteContext context,
            @NotNull ElifClause elifClause,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        resolveSupportedRoot(context, elifClause.condition());
        childSuiteResolver.resolveChildSuite(context, elifClause.body());
    }

    private void resolveWhileStatement(
            @NotNull FrontendSuiteContext context,
            @NotNull WhileStatement whileStatement,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        resolveSupportedRoot(context, whileStatement.condition());
        childSuiteResolver.resolveChildSuite(context, whileStatement.body());
    }

    private void resolveSupportedRoot(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        ownerProcedures.runTopBinding(context, root);
        ownerProcedures.runLocalTypeStabilization(context, root);
        ownerProcedures.runChainBinding(context, root);
        ownerProcedures.runExprType(context, root);
        ownerProcedures.runGateClassifier(context, root);
        ownerProcedures.runVarTypePost(context, root);
        flushStatementBoundary(context);
    }

    private void resolveUnsupportedRoot(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        ownerProcedures.runUnsupported(context, root);
        flushStatementBoundary(context);
    }

    private void flushStatementBoundary(@NotNull FrontendSuiteContext context) {
        context.typedEnvironment().flushPendingFacts();
        // Diagnostics share the statement boundary with typed facts: later statements in the same
        // suite must see upstream diagnostics without waiting for the final suite export snapshot.
        context.analysisData().updateDiagnostics(context.diagnosticManager().snapshot());
    }

    @FunctionalInterface
    public interface ChildSuiteResolver {
        void resolveChildSuite(@NotNull FrontendSuiteContext parentContext, @NotNull Block childBlock);
    }

    public interface OwnerProcedures {
        static @NotNull OwnerProcedures noop() {
            return new OwnerProcedures() {
            };
        }

        default void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        /// Runs after header expression facts are finalized into the current overlay but before the
        /// statement boundary is flushed. Phase F test classifiers use this hook to advance synthetic
        /// gate lifecycle without implementing any feature-specific rules.
        default void runGateClassifier(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runUnsupported(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }
    }
}
