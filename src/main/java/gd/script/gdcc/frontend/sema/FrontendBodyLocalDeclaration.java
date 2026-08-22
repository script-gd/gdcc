package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One ordinary local, for-iterator, or match pattern-bind declaration published by the baseline
/// inventory layer for a supported body.
///
/// `sourceOrder` is local to the owning body root and must remain contiguous from zero in AST
/// start-byte order. Production lookup still uses declaration identity plus source byte-range filters
/// for visibility; `sourceOrder` is a structural inventory fact certified at suite entry.
///
/// For `FOR_BODY` inventory the contract is stricter: exactly one [Kind#ITERATOR] entry must exist as a
/// synthetic 0th item occupying list position 0 with `sourceOrder == 0` (no negative sentinel is used;
/// `sourceOrder` is never negative). Ordinary body locals follow at `sourceOrder >= 1`.
/// For `MATCH_SECTION_BODY` inventory, [Kind#PATTERN_BIND] entries occupy a contiguous prefix
/// (`sourceOrder 0..k-1`) in pattern source order; ordinary body locals follow at `sourceOrder >= k`.
/// [FrontendBodyStructuralCompleteness] and the Interface-phase publisher both preserve this shape.
public record FrontendBodyLocalDeclaration(
        @NotNull Node declaration,
        @NotNull ScopeValue binding,
        @NotNull Kind kind,
        int sourceOrder
) {
    public FrontendBodyLocalDeclaration {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(kind, "kind");
        if (binding.declaration() != declaration) {
            throw new IllegalArgumentException("binding declaration must match the local declaration");
        }
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("sourceOrder must not be negative");
        }
    }

    public enum Kind {
        /// Loop iterator published into a `FOR_BODY` inventory. Must be the sole iterator entry and
        /// always use `sourceOrder == 0` at the head of that body's declaration list.
        ITERATOR,
        /// Match pattern bind published into a `MATCH_SECTION_BODY` inventory. Occupies a contiguous
        /// prefix of that body's declaration list in pattern source order; declaration identity is
        /// the `PatternBindingExpression`.
        PATTERN_BIND,
        /// Ordinary `var` published into a supported body inventory after any leading iterator or
        /// pattern-bind prefix.
        ORDINARY_VAR
    }
}
