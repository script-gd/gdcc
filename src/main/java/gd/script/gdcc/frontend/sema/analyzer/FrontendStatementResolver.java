package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.frontend.sema.FrontendForLoopSupport;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /// Parameter-default island entry: runs the ordinary owner pipeline on the default expression
    /// root (top binding → chain binding → expression typing) with the parameter slot type wired
    /// as the expected type. Local stabilization and var-type-post are no-ops for bare expression
    /// roots, so they are skipped; the island never enters `FrontendSuiteResolver.resolveSuite()`.
    public void resolveParameterDefault(
            @NotNull FrontendSuiteContext context,
            @NotNull Parameter parameter,
            @Nullable GdType expectedType
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(parameter, "parameter must not be null");
        var defaultValue = Objects.requireNonNull(
                parameter.defaultValue(),
                "parameter default island requires a default expression"
        );
        ownerProcedures.runTopBinding(context, defaultValue);
        ownerProcedures.runChainBinding(context, defaultValue);
        ownerProcedures.runParameterDefaultExprType(context, defaultValue, expectedType);
        flushStatementBoundary(context);
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
            case MatchStatement matchStatement -> resolveMatchStatement(context, matchStatement, childSuiteResolver);
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
        resolveForIterable(context, forStatement);
        ownerProcedures.runForIterationResolution(context, forStatement);
        ownerProcedures.runVarTypePost(context, forStatement);
        flushStatementBoundary(context);
        childSuiteResolver.resolveChildSuite(context, forStatement.body());
    }

    /// Resolves one `match` statement: subject through the ordinary owner pipeline, then pattern
    /// resolution + bind slot publication, then per-section pattern-context dispatch, guard, and
    /// child-suite body. Pattern trees never enter the generic `runSupportedRoot` walk.
    private void resolveMatchStatement(
            @NotNull FrontendSuiteContext context,
            @NotNull MatchStatement matchStatement,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        runSupportedRoot(context, matchStatement.value());
        ownerProcedures.runMatchPatternResolution(context, matchStatement);
        ownerProcedures.runVarTypePost(context, matchStatement);
        for (var section : matchStatement.sections()) {
            resolveMatchSection(context, section, childSuiteResolver);
        }
        flushStatementBoundary(context);
    }

    private void resolveMatchSection(
            @NotNull FrontendSuiteContext context,
            @NotNull MatchSection section,
            @NotNull ChildSuiteResolver childSuiteResolver
    ) {
        for (var pattern : section.patterns()) {
            resolveMatchPattern(context, pattern);
        }
        if (section.guard() != null) {
            runSupportedRoot(context, section.guard());
        }
        flushStatementBoundary(context);
        childSuiteResolver.resolveChildSuite(context, section.body());
    }

    /// Pattern-context dispatch: WILDCARD / BINDING / ARRAY / DICTIONARY stay off the ordinary
    /// expression pipeline; LITERAL / EXPRESSION leaves use `runSupportedRoot`. Nested array
    /// elements and dictionary values recurse; dictionary keys are constant expressions and use
    /// the ordinary pipeline.
    private void resolveMatchPattern(@NotNull FrontendSuiteContext context, @NotNull Expression pattern) {
        var route = FrontendMatchSupport.classifyPatternRoute(pattern);
        switch (route) {
            case WILDCARD, BINDING -> {
            }
            case ARRAY -> {
                var array = (ArrayExpression) pattern;
                for (var element : array.elements()) {
                    resolveMatchPattern(context, element);
                }
            }
            case DICTIONARY -> {
                var dictionary = (DictionaryExpression) pattern;
                for (var entry : dictionary.entries()) {
                    runSupportedRoot(context, entry.key());
                    resolveMatchPattern(context, entry.value());
                }
            }
            case LITERAL, EXPRESSION -> runSupportedRoot(context, pattern);
        }
    }

    /// Routes the for-in iterable expression through the correct owner domain.
    ///
    /// A bare `range(...)` call is recognized purely by AST shape (callee is an
    /// `IdentifierExpression` named "range"), mirroring Godot 4.5.1 behavior where same-named
    /// locals or callables do not cancel the range special case. When matched, only the call
    /// arguments enter the ordinary owner pipeline; the callee identifier and call root never
    /// produce ordinary binding, expression-type or resolved-call facts.
    private void resolveForIterable(@NotNull FrontendSuiteContext context, @NotNull ForStatement forStatement) {
        var iterable = forStatement.iterable();
        if (FrontendForLoopSupport.isBareRangeCall(iterable)) {
            var rangeCall = (CallExpression) iterable;
            for (var argument : rangeCall.arguments()) {
                runSupportedRoot(context, argument);
            }
        } else {
            runSupportedRoot(context, iterable);
        }
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

        default void runForIterationResolution(@NotNull FrontendSuiteContext context, @NotNull ForStatement forStatement) {
        }

        default void runMatchPatternResolution(
                @NotNull FrontendSuiteContext context,
                @NotNull MatchStatement matchStatement
        ) {
        }

        default void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }

        /// Parameter-default island expression typing: the bare default-expression root with the
        /// parameter slot type as expected type. Only `FrontendBodyOwnerProcedures` implements it.
        default void runParameterDefaultExprType(
                @NotNull FrontendSuiteContext context,
                @NotNull Expression defaultRoot,
                @Nullable GdType expectedType
        ) {
        }

        default void runUnsupported(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        }
    }
}
