package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Pure classification and plan-construction helper for `for-in` statements.
///
/// The helper only inspects the iterable AST shape and the already-resolved header types supplied by
/// the caller; it never reads source text, scans later statements, nor writes scopes or side tables.
/// Route selection therefore stays centralized here instead of being scattered across type-check,
/// compile gate and CFG builder.
public final class FrontendForLoopSupport {
    private static final String RANGE_FUNCTION_NAME = "range";

    private FrontendForLoopSupport() {
    }

    /// AST-shape pre-route check mirroring Godot 4.5.1: a bare `range(...)` call is recognized purely
    /// by callee shape, without any scope/name resolution, so same-named locals or callables do not
    /// cancel the range special case.
    public static boolean isBareRangeCall(@NotNull Expression iterable) {
        Objects.requireNonNull(iterable, "iterable must not be null");
        return iterable instanceof CallExpression call
                && call.callee() instanceof IdentifierExpression callee
                && RANGE_FUNCTION_NAME.equals(callee.name());
    }

    /// Classifies the iterable and builds the matching iteration plan.
    ///
    /// `declaredIteratorType` is the resolved form of `statement.iteratorType()` (null when absent);
    /// `iterableType` is the effective type of a non-range iterable (null when statically unknown).
    /// For bare `range(...)` the iterable type is irrelevant because the element type is fixed to int.
    public static @NotNull FrontendForIterationPlan buildPlan(
            @NotNull ForStatement statement,
            @Nullable GdType declaredIteratorType,
            @Nullable GdType iterableType
    ) {
        Objects.requireNonNull(statement, "statement must not be null");
        var iterable = statement.iterable();
        if (isBareRangeCall(iterable)) {
            var rangeCall = (CallExpression) iterable;
            return buildPlan(statement, FrontendForIterationRoute.RANGE_CALL, GdIntType.INT,
                    declaredIteratorType, rangeCall.arguments());
        }
        if (iterableType instanceof GdIntType) {
            return buildPlan(statement, FrontendForIterationRoute.INT_SHORTHAND, GdIntType.INT,
                    declaredIteratorType, List.of(iterable));
        }
        return buildPlan(statement, FrontendForIterationRoute.GENERIC_VARIANT, GdVariantType.VARIANT,
                declaredIteratorType, List.of(iterable));
    }

    private static @NotNull FrontendForIterationPlan buildPlan(
            @NotNull ForStatement statement,
            @NotNull FrontendForIterationRoute route,
            @NotNull GdType rawElementType,
            @Nullable GdType declaredIteratorType,
            @NotNull List<Expression> sourceOperands
    ) {
        // Without an explicit iterator type the source-facing iterator mirrors the raw element type;
        // with one, the declared type wins and a per-element conversion is required whenever the raw
        // element type differs from it.
        var exposedIteratorType = declaredIteratorType != null ? declaredIteratorType : rawElementType;
        var requiresPerElementConversion = declaredIteratorType != null
                && !FrontendAnalysisData.sameType(rawElementType, declaredIteratorType);
        return new FrontendForIterationPlan(
                statement,
                route,
                statement.iterator(),
                statement.iteratorType(),
                rawElementType,
                exposedIteratorType,
                requiresPerElementConversion,
                sourceOperands
        );
    }
}
