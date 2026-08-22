package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Expression;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Structural classification of one pattern inside a match section pattern list.
///
/// The plan intentionally carries only the route and the bind inventory: no `GdCompilerType`, no
/// expression types, no intrinsic names and no lowering protocol. Pattern nodes of the WILDCARD /
/// BINDING / ARRAY / DICTIONARY routes deliberately publish no ordinary expression facts; only
/// LITERAL / EXPRESSION leaves participate in the regular binding/chain/expression
/// pipeline (see the pattern-context dispatch contract in
/// `frontend_match_statement_implementation.md`).
///
/// @param patternNode the pattern AST node; side-table and diagnostic anchor identity
/// @param route       classified pattern route; the single fact consumers switch on
/// @param bindings    every bind inside this pattern tree in source order (including binds nested
///                    in array elements and dictionary entry values); empty for bind-free patterns
public record FrontendMatchPatternPlan(
        @NotNull Expression patternNode,
        @NotNull FrontendMatchPatternRoute route,
        @NotNull List<FrontendMatchBindingPlan> bindings
) {
    public FrontendMatchPatternPlan {
        Objects.requireNonNull(patternNode, "patternNode must not be null");
        Objects.requireNonNull(route, "route must not be null");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings must not be null"));
    }
}
