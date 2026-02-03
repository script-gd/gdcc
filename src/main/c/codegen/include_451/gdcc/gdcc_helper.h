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

static GDExtensionObjectPtr gdcc_object_from_godot_object_ptr(GDExtensionObjectPtr ptr) {
    const GDExtensionInstanceBindingCallbacks callbacks = {
        .create_callback = NULL,
        .free_callback = NULL,
        .reference_callback = NULL,
    };
    return godot_object_get_instance_binding(ptr, class_library, &callbacks);
}

static GDExtensionObjectPtr godot_new_gdcc_Object_with_Variant(const godot_Variant* value) {
    const GDExtensionObjectPtr obj = godot_new_Object_with_Variant(value);
    return gdcc_object_from_godot_object_ptr(obj);
}

#define godot_new_Variant_with_gdcc_Object(obj) godot_new_Variant_with_Object(obj->_object)

#endif //GDCC_HELPER_H
