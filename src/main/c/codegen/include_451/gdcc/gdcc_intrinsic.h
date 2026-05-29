#ifndef GDCC_INTRINSIC_H
#define GDCC_INTRINSIC_H

#include <godot_binding.h>

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
