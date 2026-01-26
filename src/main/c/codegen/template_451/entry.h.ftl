<#-- @ftlvariable name="module" type="dev.superice.gdcc.lir.LirModule" -->

#ifndef GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H
#define GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H
#include <gdextension-lite.h>

static GDExtensionClassLibraryPtr class_library = NULL;

struct GDExtensionInitializationStatus {
    godot_bool initialized;
};

void initialize(void* userdata, GDExtensionInitializationLevel p_level);
void deinitialize(void* userdata, GDExtensionInitializationLevel p_level);

#endif //GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H