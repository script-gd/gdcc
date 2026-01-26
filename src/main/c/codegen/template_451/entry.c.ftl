<#-- @ftlvariable name="module" type="dev.superice.gdcc.lir.LirModule" -->

#include "entry.h"
#include <gdcc_helper.h>
#include <implementation-macros.h>

GDE_EXPORT GDExtensionBool gdextension_entry(
    GDExtensionInterfaceGetProcAddress p_get_proc_address,
    GDExtensionClassLibraryPtr p_library,
    GDExtensionInitialization* r_initialization
) {
    gdextension_lite_initialize(p_get_proc_address);
    class_library = p_library;

    r_initialization->initialize = &initialize;
    r_initialization->deinitialize = &deinitialize;

    return true;
}

void initialize(void*, const GDExtensionInitializationLevel p_level) {
    if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    <#--  Print start loading  -->
    {
        godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Loading ${module.moduleName}..."));
        godot_print(&msg_variant, NULL, 0);
        godot_Variant_destroy(&msg_variant);
    }
}

void deinitialize(void*, GDExtensionInitializationLevel p_level) {
    <#--  Print start unloading  -->
    {
        godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Unloading ${module.moduleName}..."));
        godot_print(&msg_variant, NULL, 0);
        godot_Variant_destroy(&msg_variant);
    }
    <#--  Destroy Const StringNames and Strings  -->
    gdcc_sn_registry_destroy_all();
    gdcc_s_registry_destroy_all();
}