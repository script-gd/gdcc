package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.type.GdPackedArrayType;
import org.jetbrains.annotations.NotNull;

/// Central naming surface for the `gdcc_packed_ref.h` per-family helpers. In the Variant-backed
/// storage model every Packed*Array slot holds a `godot_Variant`, and the raw struct<->Variant
/// boundary may only be crossed through the whitelisted named helpers below. Keeping the names
/// here lets codegen reference the whitelist without re-deriving C identifier spellings at each
/// call site.
///
/// Slugs intentionally match the `GDCC_PACKED_REF_DEFINE_FAMILY` instantiations (e.g.
/// `PackedInt32Array` -> `int32_array` -> `gdcc_packed_int32_array_*`).
public final class PackedRefCNames {
    private PackedRefCNames() {
    }

    /// Per-family slug shared with `gdcc_packed_ref.h`; fail-fast on unknown families so a future
    /// packed type cannot silently fall through to a misspelled helper name.
    public static @NotNull String requireFamilySlug(@NotNull GdPackedArrayType type) {
        return switch (type.getTypeName()) {
            case "PackedByteArray" -> "byte_array";
            case "PackedInt32Array" -> "int32_array";
            case "PackedInt64Array" -> "int64_array";
            case "PackedFloat32Array" -> "float32_array";
            case "PackedFloat64Array" -> "float64_array";
            case "PackedStringArray" -> "string_array";
            case "PackedVector2Array" -> "vector2_array";
            case "PackedVector3Array" -> "vector3_array";
            case "PackedColorArray" -> "color_array";
            case "PackedVector4Array" -> "vector4_array";
            default -> throw new IllegalArgumentException(
                    "Unknown Packed*Array family for gdcc_packed_ref helper naming: " + type.getTypeName());
        };
    }

    /// `gdcc_packed_<slug>_<suffix>` helper name (e.g. `new_empty`, `wrap_temp`, `internal_ptr`).
    public static @NotNull String helperName(@NotNull GdPackedArrayType type, @NotNull String helperSuffix) {
        return "gdcc_packed_" + requireFamilySlug(type) + "_" + helperSuffix;
    }

    /// Receiver/argument base for builtin method, index and operator calls:
    /// `gdcc_packed_<slug>_internal_ptr(<variantAddrExpr>)` where the argument expression must
    /// already be the address of the Variant storage (`&$var` or a ref parameter pointer).
    public static @NotNull String internalPtrExpr(@NotNull GdPackedArrayType type, @NotNull String variantAddrExpr) {
        return helperName(type, "internal_ptr") + "(" + variantAddrExpr + ")";
    }

    /// Whitelist (b): empty-array Variant construction for default initialization.
    public static @NotNull String newEmptyExpr(@NotNull GdPackedArrayType type) {
        return helperName(type, "new_empty") + "()";
    }

    /// Whitelist (c): wrap a native struct temporary (builtin method/operator return) into a
    /// fresh Variant, destroying the temporary.
    public static @NotNull String wrapTempExpr(@NotNull GdPackedArrayType type, @NotNull String tempAddrExpr) {
        return helperName(type, "wrap_temp") + "(" + tempAddrExpr + ")";
    }

    /// Whitelist (a) inbound: ptrcall raw struct argument slot -> materialized Variant.
    public static @NotNull String variantFromStructExpr(@NotNull GdPackedArrayType type, @NotNull String structPtrExpr) {
        return helperName(type, "variant_from_struct") + "(" + structPtrExpr + ")";
    }

    /// Whitelist (a) outbound: Variant -> raw struct copy for ptrcall return slots / engine
    /// ptrcall argument materialization.
    public static @NotNull String structFromVariantExpr(@NotNull GdPackedArrayType type, @NotNull String variantAddrExpr) {
        return helperName(type, "struct_from_variant") + "(" + variantAddrExpr + ")";
    }

    /// Whitelist (d) same-family copy construction: explicit `Packed*Array(other)` and
    /// same-family `as` casts produce an independent COW copy with a fresh identity.
    public static @NotNull String newCopyExpr(@NotNull GdPackedArrayType type, @NotNull String variantAddrExpr) {
        return helperName(type, "new_copy") + "(" + variantAddrExpr + ")";
    }

    /// Whitelist (d) cross-type construction from `godot_Array`.
    public static @NotNull String newFromArrayExpr(@NotNull GdPackedArrayType type, @NotNull String arrayAddrExpr) {
        return helperName(type, "new_from_array") + "(" + arrayAddrExpr + ")";
    }

    /// Exact runtime family check used by unpack/`is` surfaces (`gdcc_packed_ref_is`).
    public static @NotNull String isExpr(@NotNull GdPackedArrayType type, @NotNull String variantAddrExpr) {
        return "gdcc_packed_ref_is(" + variantAddrExpr + ", GDEXTENSION_VARIANT_TYPE_"
                + type.getGdExtensionType().name() + ")";
    }

    /// Raw C struct type name (`godot_PackedInt32Array`) — legal only inside whitelisted boundary
    /// code (ptrcall slots, native wrapper temporaries); packed storage slots never use it.
    public static @NotNull String rawStructCType(@NotNull GdPackedArrayType type) {
        return "godot_" + type.getTypeName();
    }
}
