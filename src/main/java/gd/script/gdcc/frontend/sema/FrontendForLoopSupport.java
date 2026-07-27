package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import gd.script.gdcc.frontend.lowering.ForLoweringContractRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdCompoundVectorType;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdMetaType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdNumericType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import gd.script.gdcc.type.GdPrimitiveType;
import gd.script.gdcc.type.GdPureVectorType;
import gd.script.gdcc.type.GdRidType;
import gd.script.gdcc.type.GdStringLikeType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVectorType;
import gd.script.gdcc.type.GdVoidType;
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

    /// Classifies one resolved iterable type according to Godot's static `for-in` semantics. A null
    /// result means the expression type is not statically known, so callers retain Variant semantics.
    public static @Nullable FrontendIterableSemantics classifyIterableSemantics(@Nullable GdType iterableType) {
        if (iterableType == null) {
            return null;
        }
        return switch (iterableType) {
            case GdContainerType containerType -> switch (containerType) {
                case GdArrayType arrayType -> staticIterable(arrayType.getValueType());
                case GdDictionaryType dictionaryType -> staticIterable(dictionaryType.getKeyType());
                case GdPackedArrayType packedArrayType -> staticIterable(packedArrayType.getValueType());
            };
            case GdCompilerType _, GdMetaType _, GdNilType _, GdRidType _, GdVoidType _ ->
                    nonIterable(iterableType);
            case GdObjectType _, GdVariantType _ -> new FrontendIterableSemantics.DynamicIterable();
            case GdPrimitiveType primitiveType -> switch (primitiveType) {
                case GdBoolType _ -> nonIterable(iterableType);
                case GdNumericType numericType -> switch (numericType) {
                    case GdFloatType _ -> staticIterable(GdFloatType.FLOAT);
                    case GdIntType _ -> staticIterable(GdIntType.INT);
                };
            };
            case GdStringLikeType stringLikeType -> switch (stringLikeType) {
                case GdNodePathType _, GdStringNameType _ -> nonIterable(iterableType);
                case GdStringType _ -> staticIterable(GdStringType.STRING);
            };
            case GdVectorType vectorType -> switch (vectorType) {
                case GdCompoundVectorType _ -> nonIterable(iterableType);
                case GdPureVectorType pureVectorType -> pureVectorType.getDimension() <= 3
                        ? staticIterable(pureVectorType.getElementType())
                        : nonIterable(iterableType);
            };
        };
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
        var classification = classifyIterableSemantics(iterableType);
        var semanticElementType = switch (classification) {
            case FrontendIterableSemantics.StaticIterable(var elementType) -> elementType;
            case null -> GdVariantType.VARIANT;
            case FrontendIterableSemantics.DynamicIterable() -> GdVariantType.VARIANT;
            case FrontendIterableSemantics.NonIterable(_) -> GdVariantType.VARIANT;
        };
        var route = selectKnownRoute(iterableType);
        return buildPlan(statement, route, semanticElementType,
                declaredIteratorType, List.of(iterable));
    }

    private static @NotNull FrontendForIterationPlan buildPlan(
            @NotNull ForStatement statement,
            @NotNull FrontendForIterationRoute route,
            @NotNull GdType semanticElementType,
            @Nullable GdType declaredIteratorType,
            @NotNull List<Expression> sourceOperands
    ) {
        // Semantic compatibility and lowering materialization remain separate: the plan records only
        // the source-facing types, while lowering obtains its helper result type from its route contract.
        var exposedIteratorType = declaredIteratorType != null ? declaredIteratorType : semanticElementType;
        return new FrontendForIterationPlan(
                statement,
                route,
                statement.iterator(),
                statement.iteratorType(),
                semanticElementType,
                exposedIteratorType,
                sourceOperands
        );
    }

    private static @NotNull FrontendIterableSemantics.StaticIterable staticIterable(@NotNull GdType elementType) {
        return new FrontendIterableSemantics.StaticIterable(elementType);
    }

    private static @NotNull FrontendIterableSemantics.NonIterable nonIterable(@NotNull GdType iterableType) {
        return new FrontendIterableSemantics.NonIterable(iterableType);
    }

    /// Selects the most specific compile-ready route for a statically known iterable type.
    /// Falls back to GENERIC_VARIANT when no specialized contract is registered yet (readiness gate).
    private static @NotNull FrontendForIterationRoute selectKnownRoute(@Nullable GdType iterableType) {
        if (iterableType == null) {
            return FrontendForIterationRoute.GENERIC_VARIANT;
        }
        var candidate = switch (iterableType) {
            case GdStringType _ -> FrontendForIterationRoute.STRING;
            case GdArrayType _ -> FrontendForIterationRoute.ARRAY;
            case GdDictionaryType _ -> FrontendForIterationRoute.DICTIONARY_KEYS;
            case GdPackedArrayType packed -> packedArrayRoute(packed);
            case GdFloatType _ -> FrontendForIterationRoute.FLOAT_SHORTHAND;
            default -> null;
        };
        if (candidate != null && ForLoweringContractRegistry.get(candidate) != null) {
            return candidate;
        }
        return FrontendForIterationRoute.GENERIC_VARIANT;
    }

    private static @NotNull FrontendForIterationRoute packedArrayRoute(@NotNull GdPackedArrayType packed) {
        return ForLoweringContractRegistry.routeForPackedFamily(GdccForPackedArrayIterType.of(packed));
    }
}
