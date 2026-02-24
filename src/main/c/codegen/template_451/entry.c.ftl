<#-- @ftlvariable name="module" type="dev.superice.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="gen" type="dev.superice.gdcc.backend.c.gen.CCodegen" -->
<#include "func.ftl">
<#include "trim.ftl">

#include "entry.h"
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
    gdcc_init();
    <#--  Print start loading  -->
    {
        godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Loading ${module.moduleName}..."));
        godot_print(&msg_variant, NULL, 0);
        godot_Variant_destroy(&msg_variant);
    }
    // Register user classes
    <#list module.classDefs as classDef>
    {
        GDExtensionClassCreationInfo5 creation_info = {};
        creation_info.is_abstract = ${classDef.abstract?c};
        creation_info.is_runtime = false;
        creation_info.is_virtual = false;
        creation_info.is_exposed = true;
        creation_info.create_instance_func = ${classDef.name}_class_create_instance;
        creation_info.free_instance_func = ${classDef.name}_class_free_instance;
        creation_info.get_virtual_call_data_func = ${classDef.name}_class_get_virtual_with_data;
        creation_info.call_virtual_with_data_func = ${classDef.name}_class_call_virtual_with_data;
        creation_info.notification_func = ${classDef.name}_class_notification;
        godot_classdb_register_extension_class5(class_library,
                                                GD_STATIC_SN(u8"${classDef.name}"), GD_STATIC_SN(u8"${classDef.superName}"),
                                                &creation_info);
        ${classDef.name}_class_bind_methods();
    }
    </#list>
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

// Bind Methods for each class
<#list module.classDefs as classDef>
void ${classDef.name}_class_bind_methods() {
    godot_StringName* class_name = GD_STATIC_SN(u8"${classDef.name}");
    // Methods
    <#list classDef.functions as function>
    gdcc_bind_method${helper.renderFuncBindName(function)}(class_name, GD_STATIC_SN(u8"${function.name}"), ${classDef.name}_${function.name}<#if function.parameters?size gt function.static?then(0, 1)>,<#else>);</#if>
        <#list function.parameters as parameter>
            <#if parameter.name != "self">
                GD_STATIC_SN(u8"${parameter.name}"), GDEXTENSION_VARIANT_TYPE_${parameter.type.gdExtensionType.name()}<#if parameter_has_next>,<#else>);</#if>
            </#if>
        </#list>
    </#list>
    // Properties
    <#list classDef.properties as property>
    {
        <#if !property.static>
<#--            gdcc_bind_method${helper.renderGetterBindName(property)}(class_name, GD_STATIC_SN(u8"${property.getterFunc}"), ${classDef.name}_${property.getterFunc});-->
<#--            gdcc_bind_method${helper.renderSetterBindName(property)}(class_name, GD_STATIC_SN(u8"${property.setterFunc}"), ${classDef.name}_${property.setterFunc}, GD_STATIC_SN(u8"value"), GDEXTENSION_VARIANT_TYPE_${property.type.gdExtensionType.name()});-->
            gdcc_bind_property(class_name, GD_STATIC_SN(u8"${property.name}"), GDEXTENSION_VARIANT_TYPE_${property.type.gdExtensionType.name()}, ${helper.renderPropertyUsageEnum(property)}, GD_STATIC_SN(u8"${property.getterFunc}"), GD_STATIC_SN(u8"${property.setterFunc}"));
        </#if>
    }
    </#list>
}
</#list>

// GdExtension Methods for each class
<#list module.classDefs as classDef>
GDExtensionObjectPtr ${classDef.name}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize) {
    GDExtensionObjectPtr obj = godot_classdb_construct_object2(GD_STATIC_SN(u8"${classDef.superName}"));
    ${classDef.name}* self = godot_mem_alloc(sizeof(${classDef.name}));
    self->_object = obj;
    godot_object_set_instance(obj, GD_STATIC_SN(u8"${classDef.name}"), self);
    godot_object_set_instance_binding(obj, class_library, self, &${classDef.name}_class_binding_callbacks);
    if (p_notify_postinitialize) {
        godot_Object_notification(obj, godot_Object_NOTIFICATION_POSTINITIALIZE(), false);
    }
    return obj;
}

void ${classDef.name}_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance) {
    if (p_instance == NULL) {
        return;
    }
    ${classDef.name}* self = p_instance;
    godot_mem_free(self);
}

void ${classDef.name}_class_constructor(${classDef.name}* self) {
    if (self == NULL) {
        return;
    }
    <#list classDef.properties as property>
        self->${property.name} = ${classDef.name}_${property.initFunc}(self);
    </#list>
    <#if classDef.hasFunction("_init")>
        ${classDef.name}__init(self);
    </#if>
}

void ${classDef.name}_class_destructor(${classDef.name}* self) {
    if (self == NULL) {
        return;
    }
    <#list classDef.properties as property>
        <#if property.type.destroyable>
            <#if property.type.gdExtensionType.name() == "OBJECT">
                <#if helper.checkGdccType(property.type)>
                    try_release_object(godot_object_from_gdcc_object_ptr(self->${property.name}));
                <#else>
                    try_release_object(self->${property.name});
                </#if>
            <#else>
                ${helper.renderDestroyFunctionName(property.type)}(&(self->${property.name}));
            </#if>
        </#if>
    </#list>
}

void ${classDef.name}_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed) {
    ${classDef.name}* self = p_instance;
    if (p_what == godot_Object_NOTIFICATION_POSTINITIALIZE()) {
        ${classDef.name}_class_constructor(self);
    } else if (p_what == godot_Object_NOTIFICATION_PREDELETE()) {
        ${classDef.name}_class_destructor(self);
    }
}

void* ${classDef.name}_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name,
                                                     uint32_t p_hash) {
    // Bind virtual methods
    <#list classDef.functions as function>
        <#if helper.checkVirtualMethod(classDef, function)>
            if (godot_StringName_op_equal_StringName(p_name, GD_STATIC_SN(u8"${function.name}"))) {
                return (void*)${classDef.name}_${function.name};
            }
        </#if>
    </#list>
    return NULL;
}

void ${classDef.name}_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance,
                                                     GDExtensionConstStringNamePtr p_name,
                                                     void* p_virtual_call_userdata,
                                                     const GDExtensionConstTypePtr* p_args,
                                                     GDExtensionTypePtr r_ret) {
    // Call virtual methods
    <#list classDef.functions as function>
        <#if helper.checkVirtualMethod(classDef, function)>
            if (p_virtual_call_userdata == &${classDef.name}_${function.name}) {
                ptrcall${helper.renderFuncBindName(function)}(p_virtual_call_userdata, p_instance, p_args, r_ret);
                return;
            }
        </#if>
    </#list>
}

// Methods for ${classDef.name}

<#list classDef.functions as func>
<@funcHeader helper classDef func/> {
    <#list func.variables?values as var>
        <#if !func.checkVariableParameter(var.id)>
            ${helper.renderGdTypeInC(var.type)} $${var.id};
        </#if>
    </#list>
    ${gen.generateFuncBody(classDef, func)}
}
</#list>

</#list>