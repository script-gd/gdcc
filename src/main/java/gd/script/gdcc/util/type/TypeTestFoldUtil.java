package gd.script.gdcc.util.type;

import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

/// Shared static-type folding for GDScript `is` / `is not` (`is_instance_of`) type tests.
///
/// Frontend body lowering and the C backend both consume this decision tree so the compile-time
/// fold contract stays in one place (see `frontend_is_type_test_implementation.md`).
public final class TypeTestFoldUtil {
    private TypeTestFoldUtil() {
    }

    /// Decides whether static types prove a `value is T` outcome at compile time.
    ///
    /// Only folds outcomes that cannot change at runtime (Variant top-type target, exact match,
    /// known-null vs non-Variant target, typed-container-to-bare-Array/Dictionary, disjoint
    /// families, exact non-object mismatch including parameterized element mismatch).
    /// Parent-to-child object tests, any `Variant` operand against a non-Variant target, and
    /// bare Array/Dictionary values tested against parameterized container targets stay
    /// {@link TypeTestFoldResult#RUNTIME_OPEN} (bare slots may still carry typed metadata).
    /// Object same-type / definite upcast stay open for backend null-check (value may be null).
    /// No element covariance: `Array[Node2D] is Array[Node]` stays {@link TypeTestFoldResult#FALSE}.
    public static @NotNull TypeTestFoldResult fold(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType valueType,
            @NotNull GdType targetType
    ) {
        // Top type: `x is Variant` is always true (including null); must precede Nil / Variant-operand guards.
        if (targetType instanceof GdVariantType) {
            return TypeTestFoldResult.TRUE;
        }
        if (valueType instanceof GdVariantType) {
            return TypeTestFoldResult.RUNTIME_OPEN;
        }
        // `null` / Nil is never an instance of any non-Variant type-test target.
        if (valueType instanceof GdNilType) {
            return TypeTestFoldResult.FALSE;
        }
        if (sameStaticType(valueType, targetType)) {
            // Non-object value types (int, String, etc.) cannot be null → safe to fold true.
            // Object types may hold null at runtime → defer to backend null-check path.
            if (valueType instanceof GdObjectType) {
                return TypeTestFoldResult.RUNTIME_OPEN;
            }
            return TypeTestFoldResult.TRUE;
        }
        // Definite object upcast: static Node2D is Node.
        // Inheritance is proven, but value may be null → defer to backend null-check path.
        if (valueType instanceof GdObjectType
                && targetType instanceof GdObjectType
                && classRegistry.checkAssignable(valueType, targetType)) {
            return TypeTestFoldResult.RUNTIME_OPEN;
        }
        // Definite disjoint families (exact non-object vs object, or reverse).
        if (valueType instanceof GdObjectType != targetType instanceof GdObjectType) {
            return TypeTestFoldResult.FALSE;
        }
        // Object parent→child stays open: static Node is Node2D is not folded false.
        if (valueType instanceof GdObjectType) {
            return TypeTestFoldResult.RUNTIME_OPEN;
        }
        // Bare Array/Dictionary accept any static Array/Dictionary value (typed or bare).
        if (targetType instanceof GdArrayType targetArray
                && targetArray.isGenericArray()
                && valueType instanceof GdArrayType) {
            return TypeTestFoldResult.TRUE;
        }
        if (targetType instanceof GdDictionaryType targetDictionary
                && targetDictionary.isGenericDictionary()
                && valueType instanceof GdDictionaryType) {
            return TypeTestFoldResult.TRUE;
        }
        // Bare Array/Dictionary slots may hold values that still carry typed metadata at runtime
        // (typed→bare is assignable). Reverse bare-value is Array[T]/Dictionary[K,V] stays
        // runtime-open for the typed-metadata helper; do not fold false from the static type alone.
        if (valueType instanceof GdArrayType valueArray && valueArray.isGenericArray() && targetType instanceof GdArrayType) {
            return TypeTestFoldResult.RUNTIME_OPEN;
        }
        if (valueType instanceof GdDictionaryType valueDictionary && valueDictionary.isGenericDictionary() && targetType instanceof GdDictionaryType) {
            return TypeTestFoldResult.RUNTIME_OPEN;
        }
        // Exact non-object mismatch (int is float, Array[String] is Array[int], Packed* vs bare Array).
        return TypeTestFoldResult.FALSE;
    }

    private static boolean sameStaticType(@NotNull GdType first, @NotNull GdType second) {
        return first == second
                || (first.getClass() == second.getClass()
                && first.getTypeName().equals(second.getTypeName()));
    }
}
