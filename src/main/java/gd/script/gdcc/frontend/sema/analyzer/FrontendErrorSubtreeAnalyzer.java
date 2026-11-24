package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.RecoveredInfixExpression;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Marks the smallest damaged roots before scope publication. Diagnostic ownership stays with
/// the parser or with the semantic phase that consumes a particular recovery.
///
/// Branch headers and parameter defaults have their own recovery boundaries. A malformed
/// declaration or expression inside a branch body, by contrast, only damages its statement.
public final class FrontendErrorSubtreeAnalyzer {
    private FrontendErrorSubtreeAnalyzer() {
    }

    /// Marks every parser error subtree root of every successfully parsed unit.
    ///
    /// Parse-failed units carry an empty synthetic AST (no error nodes to find) and are excluded
    /// from the class skeleton entirely, so they are skipped here as well.
    static void annotate(@NotNull FrontendModule module, @NotNull FrontendAnalysisData analysisData) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        for (var unit : module.units()) {
            if (unit.parseFailed()) {
                continue;
            }
            for (var statement : unit.ast().statements()) {
                annotateNode(statement, statement, null, null, analysisData);
            }
        }
    }

    private static void annotateNode(
            @NotNull Node node,
            @NotNull Node nearestRecoveryRoot,
            @Nullable Node parent,
            @Nullable Node defaultRoot,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var skippedRoots = analysisData.skippedSubtreeRoots();
        if (skippedRoots.containsKey(node)) {
            return;
        }
        if (node instanceof ExpressionStatement statement && statement.expression() instanceof LambdaExpression lambda) {
            skippedRoots.put(statement, Boolean.TRUE);
            skippedRoots.put(lambda, Boolean.TRUE);
            return;
        }
        if (node instanceof VariableDeclaration variable) {
            for (var annotation : variable.recoveryAnnotations()) {
                skippedRoots.put(annotation, Boolean.TRUE);
            }
        }
        var root = recoveryRoot(node, nearestRecoveryRoot, defaultRoot);
        if (isMalformedNode(node) || node instanceof AnnotationStatement annotation
                && isDamagedAnnotation(annotation, analysisData)) {
            skippedRoots.put(root, Boolean.TRUE);
            return;
        }
        var recoveries = analysisData.parseRecoveriesByOwner().get(node);
        if (recoveries != null) {
            for (var recovery : recoveries) {
                if (!isNonExemptError(recovery)) {
                    continue;
                }
                var damaged = rootForFacet(node, parent, root, recovery.facet());
                if (damaged != null) {
                    skippedRoots.put(damaged, Boolean.TRUE);
                    if (damaged == root) {
                        return;
                    }
                }
            }
        }
        for (var child : node.getChildren()) {
            if (node instanceof Parameter parameter && child == parameter.defaultValue()) {
                annotateNode(child, child, node, child, analysisData);
            } else {
                annotateNode(child, root, node, defaultRoot, analysisData);
            }
        }
    }

    private static @NotNull Node recoveryRoot(
            @NotNull Node node,
            @NotNull Node nearestRecoveryRoot,
            @Nullable Node defaultRoot
    ) {
        if (node instanceof AnnotationStatement || node instanceof ErrorStatement || node instanceof UnknownStatement) {
            return node;
        }
        if (defaultRoot != null) {
            return defaultRoot;
        }
        return node instanceof Statement || node instanceof ElifClause || node instanceof MatchSection
                ? node : nearestRecoveryRoot;
    }

    private static boolean isMalformedNode(@NotNull Node node) {
        return switch (node) {
            case ErrorExpression _, UnknownExpression _, RecoveredInfixExpression _, ErrorStatement _,
                 UnknownStatement _ -> true;
            case PatternBindingExpression binding -> StringUtil.trimToNull(binding.name()) == null;
            case ForStatement loop -> StringUtil.trimToNull(loop.iterator()) == null;
            case VariableDeclaration variable -> StringUtil.trimToNull(variable.name()) == null;
            case FunctionDeclaration function -> StringUtil.trimToNull(function.name()) == null;
            case SignalStatement signal -> StringUtil.trimToNull(signal.name()) == null;
            default -> false;
        };
    }

    /// The same predicate applies before and after skeleton publication. Missing member names
    /// leave their receiver available to completion; missing header colons leave the body intact.
    public static boolean isNonExemptError(@NotNull FrontendParseRecovery recovery) {
        return recovery.severity() == FrontendDiagnosticSeverity.ERROR && switch (recovery.code()) {
            case DiagnosticCodes.ATTRIBUTE_MISSING_NAME, DiagnosticCodes.SUPER_MISSING_NAME,
                 DiagnosticCodes.SUPER_MISSING_CALL, DiagnosticCodes.SUPER_MISSING_ACCESS,
                 DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON, DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON -> false;
            default -> true;
        };
    }

    /// Skeleton metadata must not consume malformed source annotations before this traversal runs.
    public static boolean isDamagedAnnotation(
            @NotNull AnnotationStatement annotation,
            @NotNull FrontendAnalysisData analysisData
    ) {
        if (StringUtil.trimToNull(annotation.name()) == null) {
            return true;
        }
        var recoveries = analysisData.parseRecoveriesByOwner().get(annotation);
        return recoveries != null && recoveries.stream().anyMatch(recovery ->
                isNonExemptError(recovery) && ("annotation.arguments".equals(recovery.facet())
                        || "annotation.target".equals(recovery.facet())))
                || annotation.arguments().stream().anyMatch(FrontendErrorSubtreeAnalyzer::containsMalformedExpression);
    }

    private static boolean containsMalformedExpression(@NotNull Node node) {
        if (node instanceof ErrorExpression || node instanceof UnknownExpression || node instanceof RecoveredInfixExpression) {
            return true;
        }
        for (var child : node.getChildren()) {
            if (containsMalformedExpression(child)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Node rootForFacet(
            @NotNull Node node,
            @Nullable Node parent,
            @NotNull Node root,
            @Nullable String facet
    ) {
        if (facet == null) {
            return null;
        }
        return switch (facet) {
            case "parameter.default" -> node instanceof Parameter parameter ? parameter.defaultValue() : null;
            case "declaration.name" -> node instanceof Parameter ? null : root;
            case "type.argument" -> isCallableTypeSlot(node instanceof TypeRef ? parent : node) ? null : root;
            case "match.section" -> node instanceof MatchStatement match && !match.sections().isEmpty() ? null : root;
            case "return.value", "expression", "if.condition", "elif.condition", "while.condition",
                 "for.iterable", "for.iterator", "assert.arguments", "assert.condition", "assert.message",
                 "match.value", "match.pattern", "match.guard", "match.dictionary_pattern", "dictionary.entry",
                 "variable.type", "variable.initializer", "variable.property", "constant.type", "constant.value",
                 "enum.body", "enum.value", "annotation.arguments", "annotation.target" -> root;
            // Keep class bodies and extends clauses, callable headers, suite bodies, parameter
            // types, signal parameters, and statement control/trailing facets live for their
            // owners. Unknown facets also leave the subtree live.
            default -> null;
        };
    }

    private static boolean isCallableTypeSlot(@Nullable Node owner) {
        return owner instanceof Parameter || owner instanceof FunctionDeclaration
                || owner instanceof ConstructorDeclaration || owner instanceof LambdaExpression
                || owner instanceof SignalStatement;
    }
}
