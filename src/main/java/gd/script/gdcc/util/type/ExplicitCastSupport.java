package gd.script.gdcc.util.type;

import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdExtensionTypeEnum;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared pure classifier for GDScript {@code value as T} explicit casts.
///
/// This is the single truth source for static hard-cast validity, type-check error routing,
/// body-lowering LIR choice, and backend defensive re-check. It encodes Godot
/// {@code Variant::can_convert} (not {@code can_convert_strict}; identical on 4.5.1 and 4.7.1)
/// plus Object same-chain bidirectional rules. It must not call frontend implicit-boundary
/// helpers or treat {@link ClassRegistry#checkAssignable} as the full {@code as} rule.
///
/// See {@code doc/module_impl/frontend/frontend_cast_expression_implementation.md}.
public final class ExplicitCastSupport {
    private ExplicitCastSupport() {
    }

    /// Classifies an already-resolved source/target pair for explicit cast.
    ///
    /// Compiler-only types fail-fast: they must never reach ordinary cast surfaces.
    /// {@code void} and {@code Nil} targets are unsupported source-facing cast targets and
    /// yield {@link ExplicitCastDecision#INVALID}.
    public static @NotNull ExplicitCastDecision classify(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
        var registry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var source = Objects.requireNonNull(sourceType, "sourceType must not be null");
        var target = Objects.requireNonNull(targetType, "targetType must not be null");
        TypeCheckUtil.requireNonCompilerOnly(source, "explicit cast source type");
        TypeCheckUtil.requireNonCompilerOnly(target, "explicit cast target type");

        // Source-facing cast never accepts void/null as the RHS type specifier.
        if (source instanceof GdVoidType || target instanceof GdVoidType || target instanceof GdNilType) {
            return ExplicitCastDecision.INVALID;
        }

        if (sameStaticType(source, target)) {
            return ExplicitCastDecision.IDENTITY;
        }

        // `as Variant`: identity for Variant source, pack for every other runtime value type.
        if (target instanceof GdVariantType) {
            return source instanceof GdVariantType
                    ? ExplicitCastDecision.IDENTITY
                    : ExplicitCastDecision.PACK_TO_VARIANT;
        }

        // Object targets: same inheritance chain only (Godot reduce_cast object rule).
        if (target instanceof GdObjectType) {
            return classifyObjectTarget(registry, source, (GdObjectType) target);
        }

        // Variant / DYNAMIC-open sources stay runtime-open for every non-Object hard target.
        // DYNAMIC is not a GdType; callers pass Variant for runtime-open expression status.
        if (source instanceof GdVariantType) {
            return isRuntimeBuiltinTarget(target)
                    ? ExplicitCastDecision.BUILTIN_RUNTIME_CAST
                    : ExplicitCastDecision.INVALID;
        }

        // Parameterized container → generic bare container keeps the same base representation.
        if (isParameterizedToGenericContainer(source, target)) {
            return ExplicitCastDecision.IDENTITY;
        }

        // Builtin / packed / bare-or-parameterized container / RID path via can_convert base enums.
        var sourceExt = extensionTypeForCast(source);
        var targetExt = extensionTypeForCast(target);
        if (sourceExt != null && targetExt != null && canConvert(sourceExt, targetExt)) {
            return ExplicitCastDecision.BUILTIN_RUNTIME_CAST;
        }
        return ExplicitCastDecision.INVALID;
    }

    /// Whether the pair is a statically allowed explicit cast (not {@link ExplicitCastDecision#INVALID}).
    public static boolean checkAllowed(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
        return classify(classRegistry, sourceType, targetType) != ExplicitCastDecision.INVALID;
    }

    private static @NotNull ExplicitCastDecision classifyObjectTarget(
            @NotNull ClassRegistry registry,
            @NotNull GdType source,
            @NotNull GdObjectType target
    ) {
        if (source instanceof GdNilType || source instanceof GdVariantType) {
            return ExplicitCastDecision.OBJECT_RUNTIME_CAST;
        }
        if (!(source instanceof GdObjectType sourceObject)) {
            return ExplicitCastDecision.INVALID;
        }
        // Same type already returned IDENTITY above; remaining object pairs use chain relation.
        if (registry.checkAssignable(sourceObject, target)) {
            return ExplicitCastDecision.OBJECT_UPCAST;
        }
        if (registry.checkAssignable(target, sourceObject)) {
            return ExplicitCastDecision.OBJECT_RUNTIME_CAST;
        }
        return ExplicitCastDecision.INVALID;
    }

    private static boolean isParameterizedToGenericContainer(@NotNull GdType source, @NotNull GdType target) {
        if (source instanceof GdArrayType && target instanceof GdArrayType targetArray && targetArray.isGenericArray()) {
            return true;
        }
        return source instanceof GdDictionaryType
                && target instanceof GdDictionaryType targetDictionary
                && targetDictionary.isGenericDictionary();
    }

    private static boolean isRuntimeBuiltinTarget(@NotNull GdType target) {
        var extension = extensionTypeForCast(target);
        return extension != null && extension != GdExtensionTypeEnum.OBJECT && extension != GdExtensionTypeEnum.NIL;
    }

    /// Base Variant type used by Godot {@code can_convert} / construct for this GdType.
    /// Parameterized {@code Array[T]} / {@code Dictionary[K, V]} contribute only ARRAY / DICTIONARY.
    private static @Nullable GdExtensionTypeEnum extensionTypeForCast(@NotNull GdType type) {
        if (type instanceof GdObjectType) {
            return GdExtensionTypeEnum.OBJECT;
        }
        return type.getGdExtensionType();
    }

    private static boolean sameStaticType(@NotNull GdType first, @NotNull GdType second) {
        return first == second
                || (first.getClass() == second.getClass()
                && first.getTypeName().equals(second.getTypeName()));
    }

    /// Godot {@code Variant::can_convert} ({@code core/variant/variant.cpp}; table identical on
    /// 4.5.1-stable and 4.7.1-stable; project runtime ABI remains 4.5.1).
    /// Identity is handled by the caller; trailing {@code NIL} sentinels in Godot lists are not types.
    @SuppressWarnings("DataFlowIssue")
    static boolean canConvert(@NotNull GdExtensionTypeEnum from, @NotNull GdExtensionTypeEnum to) {
        if (from == to) {
            return true;
        }
        if (to == GdExtensionTypeEnum.NIL) {
            return true;
        }
        if (from == GdExtensionTypeEnum.NIL) {
            return to == GdExtensionTypeEnum.OBJECT;
        }
        return switch (to) {
            case BOOL -> from == GdExtensionTypeEnum.INT
                    || from == GdExtensionTypeEnum.FLOAT
                    || from == GdExtensionTypeEnum.STRING;
            case INT -> from == GdExtensionTypeEnum.BOOL
                    || from == GdExtensionTypeEnum.FLOAT
                    || from == GdExtensionTypeEnum.STRING;
            case FLOAT -> from == GdExtensionTypeEnum.BOOL
                    || from == GdExtensionTypeEnum.INT
                    || from == GdExtensionTypeEnum.STRING;
            // STRING accepts every builtin except OBJECT (NIL already handled above).
            case STRING -> from != GdExtensionTypeEnum.OBJECT;
            case VECTOR2 -> from == GdExtensionTypeEnum.VECTOR2I;
            case VECTOR2I -> from == GdExtensionTypeEnum.VECTOR2;
            case RECT2 -> from == GdExtensionTypeEnum.RECT2I;
            case RECT2I -> from == GdExtensionTypeEnum.RECT2;
            case VECTOR3 -> from == GdExtensionTypeEnum.VECTOR3I;
            case VECTOR3I -> from == GdExtensionTypeEnum.VECTOR3;
            case VECTOR4 -> from == GdExtensionTypeEnum.VECTOR4I;
            case VECTOR4I -> from == GdExtensionTypeEnum.VECTOR4;
            case TRANSFORM2D -> from == GdExtensionTypeEnum.TRANSFORM3D;
            case QUATERNION -> from == GdExtensionTypeEnum.BASIS;
            case BASIS -> from == GdExtensionTypeEnum.QUATERNION;
            case TRANSFORM3D -> from == GdExtensionTypeEnum.TRANSFORM2D
                    || from == GdExtensionTypeEnum.QUATERNION
                    || from == GdExtensionTypeEnum.BASIS
                    || from == GdExtensionTypeEnum.PROJECTION;
            case PROJECTION -> from == GdExtensionTypeEnum.TRANSFORM3D;
            case COLOR -> from == GdExtensionTypeEnum.STRING || from == GdExtensionTypeEnum.INT;
            case RID -> from == GdExtensionTypeEnum.OBJECT;
            case STRING_NAME, NODE_PATH -> from == GdExtensionTypeEnum.STRING;
            case ARRAY -> isPackedArray(from);
            case PACKED_BYTE_ARRAY, PACKED_INT32_ARRAY, PACKED_INT64_ARRAY,
                 PACKED_FLOAT32_ARRAY, PACKED_FLOAT64_ARRAY, PACKED_STRING_ARRAY,
                 PACKED_VECTOR2_ARRAY, PACKED_VECTOR3_ARRAY, PACKED_COLOR_ARRAY,
                 PACKED_VECTOR4_ARRAY -> from == GdExtensionTypeEnum.ARRAY;
            // No non-identity convert case in Godot for these targets.
            case OBJECT, PLANE, AABB, DICTIONARY, CALLABLE, SIGNAL, NIL -> false;
        };
    }

    private static boolean isPackedArray(@NotNull GdExtensionTypeEnum type) {
        return switch (type) {
            case PACKED_BYTE_ARRAY, PACKED_INT32_ARRAY, PACKED_INT64_ARRAY,
                 PACKED_FLOAT32_ARRAY, PACKED_FLOAT64_ARRAY, PACKED_STRING_ARRAY,
                 PACKED_VECTOR2_ARRAY, PACKED_VECTOR3_ARRAY, PACKED_COLOR_ARRAY,
                 PACKED_VECTOR4_ARRAY -> true;
            default -> false;
        };
    }
}
