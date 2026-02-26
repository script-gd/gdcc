#ifndef GDCC_HELPER_H
#define GDCC_HELPER_H

#include <gdextension-lite.h>
#include <gdcc_string_name.h>
#include <gdcc_string.h>
#include <gdcc_call.h>
#include <gdcc_bind.h>

#if !defined(GDE_EXPORT)
#if defined(_WIN32)
#define GDE_EXPORT __declspec(dllexport)
#elif defined(__GNUC__)
#define GDE_EXPORT __attribute__((visibility("default")))
#else
#define GDE_EXPORT
#endif
#endif

/// Object Property Getters

#define GDCC_DEFINE_OBJECT_GETTER(ReturnType, ReturnTypeName)                                 \
static inline ReturnType gdcc_object_get_##ReturnTypeName(                                    \
    GDExtensionConstObjectPtr obj, const godot_StringName* property_name) {                   \
        godot_Variant result = godot_Object_get(obj, property_name);                          \
        ReturnType value = godot_new_##ReturnTypeName##_with_Variant(&result);                \
        godot_variant_destroy(&result);                                                       \
    return value;                                                                             \
}

GDCC_DEFINE_OBJECT_GETTER(godot_float, float)
GDCC_DEFINE_OBJECT_GETTER(godot_bool, bool)
GDCC_DEFINE_OBJECT_GETTER(godot_int, int)
GDCC_DEFINE_OBJECT_GETTER(godot_Vector3, Vector3)

#define GDCC_DEFINE_OBJECT_SETTER(ValueType, ValueTypeName)                                \
static inline void gdcc_object_set_##ValueTypeName(                                        \
    GDExtensionObjectPtr obj, const godot_StringName* property_name, ValueType value) {    \
    godot_Variant v = godot_new_Variant_with_##ValueTypeName(value);                       \
    godot_Object_set(obj, property_name, &v);                                              \
    godot_variant_destroy(&v);                                                             \
}

GDCC_DEFINE_OBJECT_SETTER(godot_float, float)
GDCC_DEFINE_OBJECT_SETTER(godot_bool, bool)
GDCC_DEFINE_OBJECT_SETTER(godot_int, int)
GDCC_DEFINE_OBJECT_SETTER(godot_Vector3*, Vector3)

#define godot_Nil godot_Variant
#define godot_TypedDictionary(key, value)  godot_Dictionary

static godot_Engine* _gd_engine;

static void gdcc_init() {
    _gd_engine = godot_Engine_singleton();
}

static bool gdcc_is_editor_hint() {
    return godot_Engine_is_editor_hint(_gd_engine);
}

static void own_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_RefCounted* rc = obj;
    godot_RefCounted_reference(rc);
}

static void try_own_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_StringName class_name;
    if (!godot_object_get_class_name(obj, class_library, &class_name)) {
        return;
    }
    if (godot_ClassDB_is_parent_class(godot_ClassDB_singleton(), &class_name, GD_STATIC_SN(u8"RefCounted"))) {
        godot_RefCounted* rc = obj;
        godot_RefCounted_reference(rc);
    }
}

static void release_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_RefCounted* rc = obj;
    godot_RefCounted_unreference(rc);
}

static void try_release_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_StringName class_name;
    if (!godot_object_get_class_name(obj, class_library, &class_name)) {
        return;
    }
    if (godot_ClassDB_is_parent_class(godot_ClassDB_singleton(), &class_name, GD_STATIC_SN(u8"RefCounted"))) {
        godot_RefCounted* rc = obj;
        godot_RefCounted_unreference(rc);
    }
}

static void try_destroy_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_StringName class_name;
    if (!godot_object_get_class_name(obj, class_library, &class_name)) {
        return;
    }
    if (godot_ClassDB_is_parent_class(godot_ClassDB_singleton(), &class_name, GD_STATIC_SN(u8"RefCounted"))) {
        godot_RefCounted* rc = obj;
        godot_RefCounted_unreference(rc);
    } else {
        godot_object_destroy(obj);
    }
}

static GDExtensionClassInstancePtr gdcc_object_from_godot_object_ptr(GDExtensionObjectPtr ptr) {
    const GDExtensionInstanceBindingCallbacks callbacks = {
        .create_callback = NULL,
        .free_callback = NULL,
        .reference_callback = NULL,
    };
    return godot_object_get_instance_binding(ptr, class_library, &callbacks);
}

#define godot_object_from_gdcc_object_ptr(obj) ({ __typeof__(obj) _o = (obj); _o ? _o->_object : NULL; })

static GDExtensionClassInstancePtr godot_new_gdcc_Object_with_Variant(const godot_Variant* value) {
    const GDExtensionObjectPtr obj = godot_new_Object_with_Variant(value);
    return gdcc_object_from_godot_object_ptr(obj);
}

#define godot_new_Variant_with_gdcc_Object(obj) godot_new_Variant_with_Object(godot_object_from_gdcc_object_ptr(obj))

static godot_Transform2D godot_new_Transform2D_with_float_float_float_float_float_float(
    godot_float xx, godot_float xy, godot_float yx, godot_float yy, godot_float tx, godot_float ty
) {
    godot_Vector2 x = godot_new_Vector2_with_float_float(xx, xy);
    godot_Vector2 y = godot_new_Vector2_with_float_float(yx, yy);
    godot_Vector2 origin = godot_new_Vector2_with_float_float(tx, ty);
    godot_Transform2D t = godot_new_Transform2D_with_Vector2_Vector2_Vector2(&x, &y, &origin);
    return t;
}

static godot_Transform3D godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float(
    godot_float xx, godot_float xy, godot_float xz, godot_float yx, godot_float yy, godot_float yz, godot_float zx, godot_float zy, godot_float zz, godot_float tx, godot_float ty, godot_float tz
) {
    godot_Vector3 x = godot_new_Vector3_with_float_float_float(xx, xy, xz);
    godot_Vector3 y = godot_new_Vector3_with_float_float_float(yx, yy, yz);
    godot_Vector3 z = godot_new_Vector3_with_float_float_float(zx, zy, zz);
    godot_Vector3 origin = godot_new_Vector3_with_float_float_float(tx, ty, tz);
    godot_Transform3D t = godot_new_Transform3D_with_Vector3_Vector3_Vector3_Vector3(&x, &y, &z, &origin);
    return t;
}

static godot_Basis godot_new_Basis_with_float_float_float_float_float_float_float_float_float(
    godot_float xx, godot_float xy, godot_float xz, godot_float yx, godot_float yy, godot_float yz, godot_float zx, godot_float zy, godot_float zz
) {
    godot_Vector3 x = godot_new_Vector3_with_float_float_float(xx, xy, xz);
    godot_Vector3 y = godot_new_Vector3_with_float_float_float(yx, yy, yz);
    godot_Vector3 z = godot_new_Vector3_with_float_float_float(zx, zy, zz);
    godot_Basis b = godot_new_Basis_with_Vector3_Vector3_Vector3(&x, &y, &z);
    return b;
}

static godot_Projection godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float(
    godot_float left, godot_float right, godot_float bottom, godot_float top, godot_float z_near, godot_float z_far, godot_float fov, godot_float aspect, godot_float focal_length, godot_float fov_horizontal, godot_float fov_vertical, godot_float fov_diagonal, godot_float orthogonal_size, godot_float orthogonal_aspect, godot_float orthogonal_near, godot_float orthogonal_far
) {
    godot_Vector4 params = godot_new_Vector4_with_float_float_float_float(left, right, bottom, top);
    godot_Vector4 params2 = godot_new_Vector4_with_float_float_float_float(z_near, z_far, fov, aspect);
    godot_Vector4 params3 = godot_new_Vector4_with_float_float_float_float(focal_length, fov_horizontal, fov_vertical, fov_diagonal);
    godot_Vector4 params4 = godot_new_Vector4_with_float_float_float_float(orthogonal_size, orthogonal_aspect, orthogonal_near, orthogonal_far);
    godot_Projection p = godot_new_Projection_with_Vector4_Vector4_Vector4_Vector4(&params, &params2, &params3, &params4);
    return p;
}

/// @param self
/// @param method
/// @param file_name The name of the source file where the call is made, used for error reporting. If NULL, it will be treated as "<unknown>".
/// @param line_number
/// @param argv
/// @param argc
static godot_Variant godot_Variant_call(
    godot_Variant* self, const godot_StringName *method,
    const char* file_name, int line_number,
    const godot_Variant **argv, godot_int argc
) {
    godot_Variant ret;
    GDExtensionCallError error;
    godot_variant_call(self, method, (GDExtensionConstVariantPtr*) argv, argc, &ret, &error);
    if (error.error != GDEXTENSION_CALL_OK) {
        const char* src_file = file_name ? file_name : "<unknown>";
        char method_name[256];
        gdcc_string_name_to_utf8(method, method_name, sizeof(method_name));

        char desc[512];
        switch (error.error) {
        case GDEXTENSION_CALL_ERROR_INVALID_METHOD:
            snprintf(desc, sizeof(desc), "Invalid method '%s'", method_name);
            break;
        case GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT: {
            char expected_type_name[64];
            godot_String expected_str;
            godot_variant_get_type_name(error.expected, &expected_str);
            godot_string_to_utf8_chars(&expected_str, expected_type_name, sizeof(expected_type_name) - 1);
            GDExtensionInt elen = godot_String_length(&expected_str);
            if (elen >= (GDExtensionInt)sizeof(expected_type_name)) elen = sizeof(expected_type_name) - 1;
            expected_type_name[elen] = '\0';
            godot_String_destroy(&expected_str);
            snprintf(desc, sizeof(desc), "Invalid argument #%d for method '%s': expected type '%s'",
                     error.argument, method_name, expected_type_name);
            break;
        }
        case GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS:
            snprintf(desc, sizeof(desc), "Too many arguments for method '%s': expected %d, got %lld",
                     method_name, error.expected, argc);
            break;
        case GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS:
            snprintf(desc, sizeof(desc), "Too few arguments for method '%s': expected %d, got %lld",
                     method_name, error.expected, argc);
            break;
        case GDEXTENSION_CALL_ERROR_INSTANCE_IS_NULL:
            snprintf(desc, sizeof(desc), "Instance is null when calling method '%s'", method_name);
            break;
        case GDEXTENSION_CALL_ERROR_METHOD_NOT_CONST:
            snprintf(desc, sizeof(desc), "Method '%s' is not const", method_name);
            break;
        default:
            snprintf(desc, sizeof(desc), "Unknown error calling method '%s'", method_name);
            break;
        }
        godot_print_error(desc, "godot_Variant_call", src_file, line_number, true);
        return godot_new_Variant_nil();
    }
    return ret;
}

#endif //GDCC_HELPER_H
