package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.TypeRef;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Frozen semantic fact describing how one `ForStatement` iterates.
///
/// Published by the for-iteration resolution owner and shared by type-check, compile gate and CFG
/// builder as the single source of route/element-type truth. It intentionally carries only
/// source-visible facts: no `GdCompilerType`, no intrinsic names and no lowering protocol. The
/// lowering contract (iterator state type and operation descriptors) is derived separately from
/// `gd.script.gdcc.frontend.lowering.ForLoweringContractRegistry`.
///
/// @param statement owning `ForStatement`; also the side-table key and the iterator declaration
///                  identity, so it must be identity-equal across plan, region and slot metadata
/// @param route classified iteration scheme (range call, int shorthand, generic Variant, ...); the
///              single fact type-check, compile gate and CFG builder switch on
/// @param iteratorName source-facing iterator variable name (for example `i`); equal to
///                     `statement.iterator()`, never a synthetic hidden-slot name
/// @param declaredIteratorType explicit source `TypeRef` from `for i: Type in expr`, or null when the
///                             iterator type is inferred; retained as a source fact, not resolved here
/// @param rawElementType element type produced by the runtime helper/intrinsic for `route` (int for
///                       range/int shorthand, Variant for generic); drives iterator slot refinement
/// @param exposedIteratorType source-facing type the iterator local shows inside the body; the declared
///                            type when present, otherwise `rawElementType`; never a compiler-only type
/// @param requiresPerElementConversion true when the raw element must be converted to the exposed type
///                                     (declared type present and different from `rawElementType`)
/// @param sourceOperands source expressions feeding the loop, preserved verbatim and never fabricated:
///                       the `range(...)` arguments for RANGE_CALL, or the single stop/iterable
///                       expression for INT_SHORTHAND / GENERIC_VARIANT
public record FrontendForIterationPlan(
        @NotNull ForStatement statement,
        @NotNull FrontendForIterationRoute route,
        @NotNull String iteratorName,
        @Nullable TypeRef declaredIteratorType,
        @NotNull GdType rawElementType,
        @NotNull GdType exposedIteratorType,
        boolean requiresPerElementConversion,
        @NotNull List<Expression> sourceOperands
) {
    public FrontendForIterationPlan {
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(iteratorName, "iteratorName must not be null");
        if (iteratorName.isBlank()) {
            throw new IllegalArgumentException("iteratorName must not be blank");
        }
        Objects.requireNonNull(rawElementType, "rawElementType must not be null");
        Objects.requireNonNull(exposedIteratorType, "exposedIteratorType must not be null");
        sourceOperands = List.copyOf(Objects.requireNonNull(sourceOperands, "sourceOperands must not be null"));
    }

    /// Logical equivalence used for idempotent merge and conflict detection in the published
    /// `forIterationPlans()` side table. It is deliberately different from the record's structural
    /// `equals`:
    ///
    /// - `statement` is ignored because the side table is keyed by `ForStatement` identity, so two
    ///   plans compared here always share the same key already.
    /// - Element types are compared by class plus type name (via `FrontendAnalysisData.sameType`)
    ///   instead of identity, so republishing a logically equivalent type instance stays idempotent
    ///   rather than being reported as a conflict (`equals` would treat distinct instances as unequal).
    /// - `declaredIteratorType` and `sourceOperands` are compared by AST identity instead of structural
    ///   equals, because they are the very same source nodes and must not be matched by shape.
    public static boolean samePlan(
            @NotNull FrontendForIterationPlan first,
            @NotNull FrontendForIterationPlan second
    ) {
        return first.route() == second.route()
                && first.iteratorName().equals(second.iteratorName())
                && first.declaredIteratorType() == second.declaredIteratorType()
                && FrontendAnalysisData.sameType(first.rawElementType(), second.rawElementType())
                && FrontendAnalysisData.sameType(first.exposedIteratorType(), second.exposedIteratorType())
                && first.requiresPerElementConversion() == second.requiresPerElementConversion()
                && sameOperandList(first.sourceOperands(), second.sourceOperands());
    }

    private static boolean sameOperandList(
            @NotNull List<? extends Expression> first,
            @NotNull List<? extends Expression> second
    ) {
        if (first.size() != second.size()) {
            return false;
        }
        for (var i = 0; i < first.size(); i++) {
            if (first.get(i) != second.get(i)) {
                return false;
            }
        }
        return true;
    }
}
