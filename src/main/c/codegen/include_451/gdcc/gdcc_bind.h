#ifndef GDCC_BIND_METHOD_H
#define GDCC_BIND_METHOD_H

#include <gdextension-lite.h>

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

static void gdcc_bind_property(
    const godot_StringName* class_name,
    const godot_StringName* name,
    const GDExtensionVariantType type,
    const godot_PropertyUsageFlags usage_flags,
    const godot_StringName* getter,
    const godot_StringName* setter) {
    godot_StringName class_string_name = godot_new_StringName_with_StringName(class_name);
    const GDExtensionPropertyInfo info = gdcc_make_property_full(type, name, godot_PROPERTY_HINT_NONE,
        GD_STATIC_S(u8""), class_name, usage_flags);
    godot_StringName getter_name = godot_new_StringName_with_StringName(getter);
    godot_StringName setter_name = godot_new_StringName_with_StringName(setter);

    godot_classdb_register_extension_class_property(class_library, &class_string_name, &info, &setter_name,
                                                    &getter_name);

    godot_StringName_destroy(&class_string_name);
    gdcc_destruct_property(&info);
    godot_StringName_destroy(&getter_name);
    godot_StringName_destroy(&setter_name);
}

#endif //GDCC_BIND_METHOD_H
