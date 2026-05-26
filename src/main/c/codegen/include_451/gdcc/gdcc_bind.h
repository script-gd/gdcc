#ifndef GDCC_BIND_METHOD_H
#define GDCC_BIND_METHOD_H

#include <godot_binding.h>
#include <gdcc_string.h>
#include <gdcc_string_name.h>

#ifndef GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR
#define GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(                                             \
        accessor_name,                                                                        \
        owner_u8_literal,                                                                     \
        method_u8_literal,                                                                    \
        owner_text_literal,                                                                   \
        method_text_literal,                                                                  \
        primary_hash_value,                                                                   \
        compatibility_hash_count_value,                                                       \
        ...)                                                                                  \
static inline GDExtensionBool accessor_name(GDExtensionMethodBindPtr* r_bind) {              \
    static GDExtensionMethodBindPtr bind = NULL;                                              \
    static const GDExtensionInt compatibility_hashes[] = { __VA_ARGS__ };                     \
    if (bind != NULL) {                                                                       \
        *r_bind = bind;                                                                       \
        return true;                                                                          \
    }                                                                                         \
    bind = godot_classdb_get_method_bind(                                                     \
            GD_STATIC_SN(owner_u8_literal),                                                   \
            GD_STATIC_SN(method_u8_literal),                                                  \
            primary_hash_value);                                                              \
    for (GDExtensionInt i = 0; bind == NULL && i < (compatibility_hash_count_value); ++i) {   \
        bind = godot_classdb_get_method_bind(                                                 \
                GD_STATIC_SN(owner_u8_literal),                                               \
                GD_STATIC_SN(method_u8_literal),                                              \
                compatibility_hashes[i]);                                                     \
    }                                                                                         \
    if (bind != NULL) {                                                                       \
        *r_bind = bind;                                                                       \
        return true;                                                                          \
    }                                                                                         \
    return gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){                           \
            .kind = "engine_method",                                                         \
            .function_name = #accessor_name,                                                  \
            .lookup_name = method_text_literal,                                               \
            .owner = owner_text_literal,                                                      \
            .has_primary_hash = true,                                                         \
            .primary_hash = primary_hash_value,                                               \
            .compatibility_hashes = (compatibility_hash_count_value) > 0                      \
                    ? compatibility_hashes : NULL,                                            \
            .compatibility_hash_count = compatibility_hash_count_value,                       \
    });                                                                                       \
}
#endif

static GDExtensionPropertyInfo gdcc_make_property_full(
    const GDExtensionVariantType type,
    const godot_StringName* name,
    const uint32_t hint,
    const godot_String* hint_string,
    const godot_StringName* class_name,
    const uint32_t usage_flags) {
    godot_StringName* prop_name = godot_mem_alloc(sizeof(godot_StringName));
    *prop_name = godot_new_StringName_with_StringName(name);
    godot_String* prop_hint_string = godot_mem_alloc(sizeof(godot_String));
    *prop_hint_string = godot_new_String_with_String(hint_string);
    godot_StringName* prop_class_name = godot_mem_alloc(sizeof(godot_StringName));
    *prop_class_name = godot_new_StringName_with_StringName(class_name);

    return (GDExtensionPropertyInfo){
        .name = prop_name,
        .type = type,
        .hint = hint,
        .hint_string = prop_hint_string,
        .class_name = prop_class_name,
        .usage = usage_flags,
    };
}

static GDExtensionPropertyInfo gdcc_make_property(
    const GDExtensionVariantType type,
    const godot_StringName* name) {
    return gdcc_make_property_full(type, name, godot_PROPERTY_HINT_NONE,
        GD_STATIC_S(u8""), GD_STATIC_SN(u8""), godot_PROPERTY_USAGE_DEFAULT);
}

static void gdcc_destruct_property(const GDExtensionPropertyInfo* info) {
    godot_StringName_destroy(info->name);
    godot_String_destroy(info->hint_string);
    godot_StringName_destroy(info->class_name);
    godot_mem_free(info->name);
    godot_mem_free(info->hint_string);
    godot_mem_free(info->class_name);
}

// Property registration now has a full metadata entry so Variant outward ABI can
// publish NIL + PROPERTY_USAGE_NIL_IS_VARIANT without hard-coding another helper shape.
// The property-class slot is still explicit because non-Variant property metadata has not
// been fully normalized yet.
static void gdcc_bind_property_full(
    const godot_StringName* owner_class_name,
    const godot_StringName* name,
    const GDExtensionVariantType type,
    const uint32_t hint,
    const godot_String* hint_string,
    const godot_StringName* property_class_name,
    const godot_PropertyUsageFlags usage_flags,
    const godot_StringName* getter,
    const godot_StringName* setter) {
    godot_StringName class_string_name = godot_new_StringName_with_StringName(owner_class_name);
    const GDExtensionPropertyInfo info = gdcc_make_property_full(type, name, hint,
        hint_string, property_class_name, usage_flags);
    godot_StringName getter_name = godot_new_StringName_with_StringName(getter);
    godot_StringName setter_name = godot_new_StringName_with_StringName(setter);

    godot_classdb_register_extension_class_property(class_library, &class_string_name, &info, &setter_name,
                                                    &getter_name);

    godot_StringName_destroy(&class_string_name);
    gdcc_destruct_property(&info);
    godot_StringName_destroy(&getter_name);
    godot_StringName_destroy(&setter_name);
}

static void gdcc_bind_property(
    const godot_StringName* class_name,
    const godot_StringName* name,
    const GDExtensionVariantType type,
    const godot_PropertyUsageFlags usage_flags,
    const godot_StringName* getter,
    const godot_StringName* setter) {
    gdcc_bind_property_full(class_name, name, type, godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8""),
        class_name, usage_flags, getter, setter);
}

#endif //GDCC_BIND_METHOD_H
