#ifndef GDCC_PACKED_REF_H
#define GDCC_PACKED_REF_H

#include <godot_binding.h>
#include <gdcc_likely.h>
#include <stdio.h>
#include <stdlib.h>

/// Variant-backed Packed*Array storage infrastructure (packed_array_reference_semantics_plan.md
/// design contract §4.1).
///
/// In the reference-semantics model every Packed*Array slot (locals, parameters, fields, coroutine
/// frames, wrappers) stores a `godot_Variant` whose internal `PackedArrayRef` is shared with all
/// aliases; identity flows exclusively through `godot_new_Variant_with_Variant` /
/// `godot_variant_destroy`. The only places where a value may cross the struct<->Variant boundary
/// are the whitelisted conversions below, each behind a named per-family helper so generated code
/// never calls the raw `godot_new_Packed*Array_with_*` / `godot_new_Variant_with_Packed*Array`
/// symbols directly (enforced by grep):
///
///   (a) ptrcall ABI boundary, both directions (plan §1.3 exception 1, §4.3.11):
///       - `gdcc_packed_<slug>_variant_from_struct`  inbound materialization of a raw arg slot
///       - `gdcc_packed_<slug>_struct_from_variant`  outbound copy written to the return slot
///       Both directions copy at the engine `Vector` layer, so mutation across the ptrcall
///       boundary is intentionally NOT shared.
///   (b) `gdcc_packed_<slug>_new_empty`  default initialization to an empty array Variant;
///       the temporary struct has no sharers at this point (plan §4.3.2). A nil Variant is never
///       a valid packed value: it has no internal pointer and method calls on it fail.
///   (c) `gdcc_packed_<slug>_wrap_temp`  wraps a native struct temporary produced by a builtin
///       method/operator return (e.g. `duplicate`, `slice`, `+`) into a fresh Variant and
///       destroys the temporary; the result is an independent new array (plan §4.3.4, §4.3.6).
///   (d) construction with arguments (plan §4.1(d), §4.3.7):
///       - `gdcc_packed_<slug>_new_copy`  same-family copy construct (`Packed*Array(other)` and
///         same-family `as` cast): produces an independent new array, never shares identity
///       - `gdcc_packed_<slug>_new_from_array`  cross-type construct from `godot_Array`
///
/// Initialization contract: `gdcc_packed_ref_init()` must run once per translation unit during
/// extension initialization (mirroring `gdcc_init`) before any other helper here is used. It
/// resolves the per-family `variant_get_ptr_internal_getter` getters exactly once and fail-fasts
/// (error print + abort) when Godot does not expose one; calling an accessor without
/// initialization fail-fasts the same way instead of dereferencing a NULL getter.

/// Reports a fatal packed-ref infrastructure error and aborts the process. These paths indicate
/// an engine/ABI mismatch or a missing init call, i.e. unrecoverable codegen-level contract
/// violations, so there is no attempt at graceful degradation. The engine log is preferred, but
/// the interface pointer table may not be resolved yet (e.g. an accessor used before
/// `godot_initialize_interface`), in which case the same diagnostic goes to stderr; abort is
/// guaranteed either way.
static _Noreturn void gdcc_packed_ref_fail(const char *desc) {
    if (gdcc_interface_print_error != NULL) {
        godot_print_error(desc, "gdcc_packed_ref", __FILE__, 0, true);
    } else {
        fputs(desc, stderr);
        fputc('\n', stderr);
        fflush(stderr);
    }
    abort();
}

/// Identity-preserving holder copy: the result shares the source's underlying packed array.
static inline godot_Variant gdcc_packed_ref_copy(const godot_Variant *src) {
    return godot_new_Variant_with_Variant(src);
}

/// Releases one Variant holder; the shared array dies with its last holder.
static inline void gdcc_packed_ref_destroy(godot_Variant *value) {
    godot_variant_destroy((GDExtensionVariantPtr)value);
}

/// Runtime family test used by `is`/cast lowering (plan §4.3.7): exact Variant kind match.
static inline godot_bool gdcc_packed_ref_is(const godot_Variant *value, GDExtensionVariantType expected_kind) {
    return value != NULL && godot_variant_get_type(value) == expected_kind;
}

/// Defines the per-family getter cache and the whitelisted conversion helpers for one
/// Packed*Array family. The getter static is per-TU (same pattern as `_gd_engine` in
/// gdcc_helper.h), which is why `gdcc_packed_ref_init()` must run in every TU that uses these.
#define GDCC_PACKED_REF_DEFINE_FAMILY(Slug, TypeName, VariantKind) \
static GDExtensionVariantGetInternalPtrFunc gdcc_packed_##Slug##_getter = NULL; \
\
/* Internal value pointer of the family Variant, used as the base for builtin method / index / \
 * operator calls (plan §4.3.3). The engine getter is exposed only in a non-const signature but \
 * does not mutate the Variant, so the const cast is safe. The caller must guarantee the Variant \
 * actually holds this family (`gdcc_packed_ref_is`); a mismatch is engine-level UB (plan §7.5) \
 * and is NOT reliably detectable here — the NULL result check below is only a backstop for an \
 * engine that does return NULL, not a type-mismatch guard. \
 * NOTE: keep macro-body comments as block comments — `//` would swallow the rest of the macro. */ \
static inline godot_Packed##TypeName *gdcc_packed_##Slug##_internal_ptr(const godot_Variant *self) { \
    if (unlikely(self == NULL)) { \
        gdcc_packed_ref_fail("gdcc_packed_" #Slug "_internal_ptr called with NULL Variant"); \
    } \
    if (unlikely(gdcc_packed_##Slug##_getter == NULL)) { \
        gdcc_packed_ref_fail("gdcc_packed_" #Slug "_internal_ptr used before gdcc_packed_ref_init()"); \
    } \
    void *internal = gdcc_packed_##Slug##_getter((GDExtensionVariantPtr)self); \
    if (unlikely(internal == NULL)) { \
        gdcc_packed_ref_fail("variant_get_ptr_internal_getter returned NULL internal pointer for " #TypeName); \
    } \
    return (godot_Packed##TypeName *)internal; \
} \
\
static inline godot_Variant gdcc_packed_##Slug##_new_empty(void) { \
    godot_Packed##TypeName temp = godot_new_Packed##TypeName(); \
    godot_Variant value = godot_new_Variant_with_Packed##TypeName(&temp); \
    godot_Packed##TypeName##_destroy(&temp); \
    return value; \
} \
\
static inline godot_Variant gdcc_packed_##Slug##_wrap_temp(godot_Packed##TypeName *temp) { \
    godot_Variant value = godot_new_Variant_with_Packed##TypeName(temp); \
    godot_Packed##TypeName##_destroy(temp); \
    return value; \
} \
\
static inline godot_Variant gdcc_packed_##Slug##_variant_from_struct(const godot_Packed##TypeName *src) { \
    return godot_new_Variant_with_Packed##TypeName(src); \
} \
\
static inline godot_Packed##TypeName gdcc_packed_##Slug##_struct_from_variant(const godot_Variant *src) { \
    return godot_new_Packed##TypeName##_with_Variant(src); \
} \
\
static inline godot_Variant gdcc_packed_##Slug##_new_copy(const godot_Variant *src) { \
    godot_Packed##TypeName temp = \
            godot_new_Packed##TypeName##_with_Packed##TypeName(gdcc_packed_##Slug##_internal_ptr(src)); \
    return gdcc_packed_##Slug##_wrap_temp(&temp); \
} \
\
static inline godot_Variant gdcc_packed_##Slug##_new_from_array(const godot_Array *from) { \
    godot_Packed##TypeName temp = godot_new_Packed##TypeName##_with_Array(from); \
    return gdcc_packed_##Slug##_wrap_temp(&temp); \
}

GDCC_PACKED_REF_DEFINE_FAMILY(byte_array, ByteArray, GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(int32_array, Int32Array, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(int64_array, Int64Array, GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(float32_array, Float32Array, GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(float64_array, Float64Array, GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(string_array, StringArray, GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(vector2_array, Vector2Array, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(vector3_array, Vector3Array, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(color_array, ColorArray, GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY)
GDCC_PACKED_REF_DEFINE_FAMILY(vector4_array, Vector4Array, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY)

/// Resolves and caches every family's internal pointer getter; fail-fast on the first missing one
/// so an engine without `variant_get_ptr_internal_getter` support never reaches a call site.
static void gdcc_packed_ref_init(void) {
    if (unlikely(gdcc_interface_variant_get_ptr_internal_getter == NULL)) {
        gdcc_packed_ref_fail("variant_get_ptr_internal_getter interface unresolved "
                "(gdcc_packed_ref_init called before godot_initialize_interface?)");
    }
#define GDCC_PACKED_REF_INIT_FAMILY(Slug, TypeName, VariantKind) \
    gdcc_packed_##Slug##_getter = godot_variant_get_ptr_internal_getter(VariantKind); \
    if (unlikely(gdcc_packed_##Slug##_getter == NULL)) { \
        gdcc_packed_ref_fail("variant_get_ptr_internal_getter unavailable for " #TypeName); \
    }

    GDCC_PACKED_REF_INIT_FAMILY(byte_array, ByteArray, GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(int32_array, Int32Array, GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(int64_array, Int64Array, GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(float32_array, Float32Array, GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(float64_array, Float64Array, GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(string_array, StringArray, GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(vector2_array, Vector2Array, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(vector3_array, Vector3Array, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(color_array, ColorArray, GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY)
    GDCC_PACKED_REF_INIT_FAMILY(vector4_array, Vector4Array, GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY)
#undef GDCC_PACKED_REF_INIT_FAMILY
}

#undef GDCC_PACKED_REF_DEFINE_FAMILY

#endif //GDCC_PACKED_REF_H
