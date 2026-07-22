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

/// Root-bounded statement dispatcher for the body SuiteResolver.
///
/// Production wiring reaches this dispatcher through `FrontendSuiteResolver`, which supplies real
/// owner procedures and publishes facts through the typed lexical environment. Tests may inject
/// custom owner procedures for traversal recording.
public class FrontendStatementResolver {
    private final @NotNull OwnerProcedures ownerProcedures;

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
            case ForStatement forStatement -> resolveForStatement(context, forStatement, childSuiteResolver);
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

    private void resolveForStatement(
            @NotNull FrontendSuiteContext context,
            @NotNull ForStatement forStatement,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        // Header facts share one statement boundary. The body is entered only through the ordinary
        // child-suite path so header typing can never become a body-entry condition.
        if (forStatement.iteratorType() != null) {
            runSupportedRoot(context, forStatement.iteratorType());
        }
        runSupportedRoot(context, forStatement.iterable());
        flushStatementBoundary(context);
        childSuiteResolver.resolveChildSuite(context, forStatement.body());
    }

    private void resolveSupportedRoot(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        runSupportedRoot(context, root);
        flushStatementBoundary(context);
    }

    private void runSupportedRoot(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        ownerProcedures.runTopBinding(context, root);
        ownerProcedures.runLocalTypeStabilization(context, root);
        ownerProcedures.runChainBinding(context, root);
        ownerProcedures.runExprType(context, root);
        ownerProcedures.runVarTypePost(context, root);
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
        default void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        default void runUnsupported(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }
    }
}
