#ifndef GDCC_INTRINSIC_H
#define GDCC_INTRINSIC_H

#include <godot_binding.h>

typedef struct gdcc_for_range_iter {
    godot_int current;
    godot_int end;
    godot_int step;
} gdcc_for_range_iter;

static inline gdcc_for_range_iter gdcc_for_range_iter_init(void) {
    return (gdcc_for_range_iter){ .current = 0, .end = 0, .step = 1 };
}

static inline void gdcc_for_range_iter_destroy(gdcc_for_range_iter *iter) {
    (void)iter;
}

static inline gdcc_for_range_iter gdcc_for_range_iter_from_bounds(
    godot_int start,
    godot_int end,
    godot_int step
) {
    if (step == 0) {
        godot_print_error("range step argument is zero", "gdcc_for_range_iter_from_bounds", "<generated>", 0, true);
        return (gdcc_for_range_iter){ .current = start, .end = end, .step = 1 };
    }
    return (gdcc_for_range_iter){ .current = start, .end = end, .step = step };
}

static inline godot_bool gdcc_for_range_iter_should_continue(const gdcc_for_range_iter *iter) {
    if (iter->step > 0) {
        return iter->current < iter->end;
    }
    return iter->current > iter->end;
}

static inline gdcc_for_range_iter gdcc_for_range_iter_next(const gdcc_for_range_iter *iter) {
    return (gdcc_for_range_iter){
        .current = iter->current + iter->step,
        .end = iter->end,
        .step = iter->step,
    };
}

static inline godot_int gdcc_for_range_iter_get(const gdcc_for_range_iter *iter) {
    return iter->current;
}

/// Wrapper-only inbound constructors for call_func arguments whose accepted Variant payload
/// runtime type differs from the published method metadata.
/// The generated wrapper must run its runtime type gate first; these helpers materialize the
/// already-accepted payload and do not set r_error themselves.
static inline godot_StringName gdcc_new_StringName_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_STRING) {
        godot_String source = godot_new_String_with_Variant(value);
        godot_StringName result = godot_new_StringName_with_String(&source);
        godot_String_destroy(&source);
        return result;
    }
    return godot_new_StringName_with_Variant(value);
}

static inline godot_String gdcc_new_String_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_STRING_NAME) {
        godot_StringName source = godot_new_StringName_with_Variant(value);
        godot_String result = godot_new_String_with_StringName(&source);
        godot_StringName_destroy(&source);
        return result;
    }
    return godot_new_String_with_Variant(value);
}

static inline godot_Vector2 gdcc_new_Vector2_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR2I) {
        godot_Vector2i source = godot_new_Vector2i_with_Variant(value);
        return godot_new_Vector2_with_Vector2i(&source);
    }
    return godot_new_Vector2_with_Variant(value);
}

static inline godot_Vector3 gdcc_new_Vector3_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR3I) {
        godot_Vector3i source = godot_new_Vector3i_with_Variant(value);
        return godot_new_Vector3_with_Vector3i(&source);
    }
    return godot_new_Vector3_with_Variant(value);
}

static inline godot_Vector4 gdcc_new_Vector4_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR4I) {
        godot_Vector4i source = godot_new_Vector4i_with_Variant(value);
        return godot_new_Vector4_with_Vector4i(&source);
    }
    return godot_new_Vector4_with_Variant(value);
}

#endif
