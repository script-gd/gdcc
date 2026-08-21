package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One `var name` binding collected from a match pattern tree.
///
/// Bindings are the only declarations a pattern can introduce; the section-level inventory and
/// slot publication use the `PatternBindingExpression` node as declaration identity, so the plan
/// preserves it verbatim instead of inventing synthetic keys.
///
/// @param name        source-facing bind variable name (for example `v`); never a synthetic name
/// @param declaration the `var name` AST node; declaration identity for inventory, slot types and
///                    CFG bind items, so it must be identity-equal across all consumers
/// @param topLevel    true when the bind is a whole section pattern (binds the match subject and
///                    may be refined to the subject static type); false when nested inside an
///                    array/dictionary pattern (binds a sub-element and stays `Variant`)
public record FrontendMatchBindingPlan(
        @NotNull String name,
        @NotNull PatternBindingExpression declaration,
        boolean topLevel
) {
    public FrontendMatchBindingPlan {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(declaration, "declaration must not be null");
    }
}
