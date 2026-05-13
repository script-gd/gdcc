#ifndef GDCC_INTRINSIC_H
#define GDCC_INTRINSIC_H

#include <gdextension-lite.h>

/// Wrapper-only inbound constructors for call_func arguments whose Variant payload may use
/// a narrower Godot runtime type than the published method metadata.
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
