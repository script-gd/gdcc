package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Pure metadata-level builtin operator type helpers shared by ordinary expression typing and
/// constant evaluation.
///
/// The helpers only translate `GodotOperator` + operand `GdType` pairs into declared return types
/// by consulting `extension_api` builtin-class operator tables: no AST, no diagnostics, no side
/// tables, no operand swapping — binary lookup always goes `leftType + operator + rightType` in
/// that order, so asymmetric pairs such as `int * Vector2` are never inferred in reverse.
/// `FrontendExpressionSemanticSupport` keeps owning the `FrontendExpressionType` composition
/// (runtime-open fallbacks, `not in`, dependency propagation) on top of these primitives.
final class FrontendBuiltinOperatorSupport {
    private FrontendBuiltinOperatorSupport() {
    }

    /// Exact unary `operator + operandType` lookup: unary entries carry an empty `rightType` in
    /// builtin metadata. Returns null when the operand type owns no matching metadata entry.
    static @Nullable GdType resolveUnaryExactReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GodotOperator operator,
            @NotNull GdType operandType
    ) {
        var builtinClass = findOperatorOwnerClass(classRegistry, operandType);
        if (builtinClass == null) {
            return null;
        }
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            if (!StringUtil.trimToEmpty(classOperator.rightType()).isEmpty()) {
                continue;
            }
            var returnType = parseOperatorReturnType(classRegistry, classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    /// Source-level special pairs that win over the builtin metadata lookup: `and`/`or` always
    /// publish `bool` regardless of operand types, object/`nil` and object/object `==`/`!=`
    /// compare identity, and `Array[T] + Array[T]` concatenation preserves the left element type.
    static @Nullable GdType resolveBinarySpecialReturnType(
            @NotNull GodotOperator operator,
            @NotNull GdType publishedLeftType,
            @NotNull GdType publishedRightType
    ) {
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            return GdBoolType.BOOL;
        }
        if ((operator == GodotOperator.EQUAL || operator == GodotOperator.NOT_EQUAL)
                && isObjectNilEqualityPair(publishedLeftType, publishedRightType)) {
            return GdBoolType.BOOL;
        }
        if ((operator == GodotOperator.EQUAL || operator == GodotOperator.NOT_EQUAL)
                && isObjectObjectEqualityPair(publishedLeftType, publishedRightType)) {
            return GdBoolType.BOOL;
        }
        if (operator == GodotOperator.ADD
                && publishedLeftType instanceof GdArrayType leftArrayType
                && publishedRightType instanceof GdArrayType rightArrayType
                && !(leftArrayType.getValueType() instanceof GdVariantType)
                && leftArrayType.getValueType().equals(rightArrayType.getValueType())) {
            return leftArrayType;
        }
        return null;
    }

    /// Exact binary `leftType + operator + rightType` lookup against builtin metadata. The owner
    /// class is resolved from the left operand only and right types match by metadata operand
    /// name, so typed containers normalize to their raw `Array`/`Dictionary` metadata names and
    /// operand order is never swapped.
    static @Nullable GdType resolveBinaryExactReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GodotOperator operator,
            @NotNull GdType leftType,
            @NotNull GdType rightType
    ) {
        var builtinClass = findOperatorOwnerClass(classRegistry, leftType);
        if (builtinClass == null) {
            return null;
        }
        var normalizedRightType = StringUtil.trimToEmpty(operatorOperandTypeName(rightType));
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            var metadataRightType = StringUtil.trimToEmpty(classOperator.rightType());
            if (metadataRightType.isEmpty() || !metadataRightType.equals(normalizedRightType)) {
                continue;
            }
            var returnType = parseOperatorReturnType(classRegistry, classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    /// Godot metadata exposes mixed int/float operator entries, but this frontend boundary does
    /// not turn ordinary typed-boundary widening into operator promotion; both ordinary typing
    /// and constant evaluation keep the pair fail-closed.
    static boolean isMixedIntFloatScalarPair(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdIntType && rightType instanceof GdFloatType
                || leftType instanceof GdFloatType && rightType instanceof GdIntType;
    }

    private static boolean isObjectNilEqualityPair(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdNilType && rightType instanceof GdNilType
                || leftType instanceof GdObjectType && rightType instanceof GdNilType
                || leftType instanceof GdNilType && rightType instanceof GdObjectType;
    }

    /// Identity equality gate for two static object types.
    ///
    /// Godot Variant `OBJECT/OBJECT` `==` / `!=` compares identity, not assignability.
    /// Any `GdObjectType` pair is accepted; `GdVariantType` is excluded by `instanceof`.
    private static boolean isObjectObjectEqualityPair(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdObjectType && rightType instanceof GdObjectType;
    }

    private static @Nullable ExtensionBuiltinClass findOperatorOwnerClass(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType operandType
    ) {
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                .findBuiltinClass(operatorOperandTypeName(operandType));
    }

    /// Typed containers keep richer source-level names such as `Array[int]`, but operator
    /// metadata is owned by the raw builtin classes and uses raw operand names for matching.
    private static @NotNull String operatorOperandTypeName(@NotNull GdType operandType) {
        if (operandType instanceof GdArrayType) {
            return "Array";
        }
        if (operandType instanceof GdDictionaryType) {
            return "Dictionary";
        }
        return operandType.getTypeName();
    }

    private static @Nullable GdType parseOperatorReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull ExtensionBuiltinClass.ClassOperator classOperator
    ) {
        var returnTypeName = StringUtil.trimToEmpty(classOperator.returnType());
        if (returnTypeName.isEmpty()) {
            return null;
        }
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                .tryResolveDeclaredType(returnTypeName);
    }
}
