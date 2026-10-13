package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Error-subtree annotation step running after skeleton publication and before scope analysis.
///
/// The tolerant parser already reported every mapped `ERROR`/`MISSING` structure as a
/// `parse.lowering` diagnostic; this step owns no diagnostics of its own (single-owner recovery
/// rule). It only lifts each parser error node to its minimal enclosing recovery root in
/// `FrontendAnalysisData.skippedSubtreeRoots()` so the scope phase and the body phase can skip
/// exactly the damaged subtree while healthy sibling subtrees keep publishing facts:
///
/// - `ErrorStatement` / `UnknownStatement` mark themselves (they already are statement roots).
/// - `ErrorExpression` / `UnknownExpression` mark the nearest enclosing `Statement`
///   (`var x = <err>` skips the whole declaration statement, `if <err>:` skips the whole `if`).
/// - Inside a `Parameter` default value the recovery root is the default expression itself, never
///   the whole callable — otherwise the callable body would lose its scope and drag the whole
///   analysis into structural failure.
/// - Roots already marked by the class skeleton (rejected enums, header rejections, ...) are left
///   untouched and their subtrees are not descended into.
///
/// Property initializers (`var hp = <err>` at class level) mark their `VariableDeclaration`; the
/// property-initializer island consumes that mark and skips only that one property.
final class FrontendErrorSubtreeAnnotator {
    /// This class is a stateless traversal utility and is not instantiated.
    private FrontendErrorSubtreeAnnotator() {
    }

    /// Marks every parser error subtree root of every successfully parsed unit.
    ///
    /// Parse-failed units carry an empty synthetic AST (no error nodes to find) and are excluded
    /// from the class skeleton entirely, so they are skipped here as well.
    static void annotate(@NotNull FrontendModule module, @NotNull FrontendAnalysisData analysisData) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        var skippedRoots = analysisData.skippedSubtreeRoots();
        for (var unit : module.units()) {
            if (unit.parseFailed()) {
                continue;
            }
            for (var statement : unit.ast().statements()) {
                annotateNode(statement, statement, skippedRoots);
            }
        }
    }

    /// Depth-first annotation walk. `nearestRecoveryRoot` is the closest enclosing statement (or
    /// the parameter-default expression root when inside a parameter default island).
    private static void annotateNode(
            @NotNull Node node,
            @NotNull Node nearestRecoveryRoot,
            @NotNull FrontendAstSideTable<Boolean> skippedRoots
    ) {
        if (skippedRoots.containsKey(node)) {
            // The skeleton already rejected this subtree; its descendants are moot.
            return;
        }
        switch (node) {
            case ErrorStatement _, UnknownStatement _ -> {
                skippedRoots.put(node, Boolean.TRUE);
                return;
            }
            case ErrorExpression _, UnknownExpression _ -> {
                skippedRoots.put(nearestRecoveryRoot, Boolean.TRUE);
                return;
            }
            // gdparser maps a missing match-binding name (`var :` in a pattern) to a blank-named
            // PatternBindingExpression rather than an error node; the parser already reported it,
            // so mark the enclosing statement damaged before the variable inventory rejects the
            // blank name structurally and drags the whole module into INTERNAL_FAILED.
            case PatternBindingExpression binding when binding.name().isBlank() -> {
                skippedRoots.put(nearestRecoveryRoot, Boolean.TRUE);
                return;
            }
            default -> {
            }
        }
        var childRecoveryRoot = node instanceof Statement ? node : nearestRecoveryRoot;
        if (node instanceof Parameter parameter && parameter.defaultValue() != null) {
            // The default expression forms its own recovery island: mark the default root rather
            // than letting the error escape to the enclosing callable declaration.
            for (var child : node.getChildren()) {
                annotateNode(child, child == parameter.defaultValue() ? child : childRecoveryRoot, skippedRoots);
            }
            return;
        }
        for (var child : node.getChildren()) {
            annotateNode(child, childRecoveryRoot, skippedRoots);
        }
    }
}
