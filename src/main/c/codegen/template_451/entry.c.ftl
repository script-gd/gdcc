<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="gd.script.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="bodyRender" type="gd.script.gdcc.backend.c.gen.binding.GenerateRenderFacade" -->
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

    r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE;
    r_initialization->userdata = NULL;
    r_initialization->initialize = &initialize;
    r_initialization->deinitialize = &deinitialize;

    return true;
}

void initialize(void* userdata, const GDExtensionInitializationLevel p_level) {
    (void)userdata;
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
    <#-- Register user classes.-->
    <#-- Registration, bind-owner lookup, and instance attach intentionally all reuse the-->
    <#-- same canonical class name directly. There is no backend-only Godot alias layer here.-->
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

void deinitialize(void* userdata, GDExtensionInitializationLevel p_level) {
    (void)userdata;
    if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
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

<#-- Bind Methods for each class.-->
<#-- The local `class_name` slot remains the canonical owner identity that registration used above.-->
<#list module.classDefs as classDef>
void ${classDef.name}_class_bind_methods() {
    godot_StringName* class_name = GD_STATIC_SN(u8"${classDef.name}");
    // Methods
    <#list classDef.functions as function>
        <#if !function.hidden && !function.lambda>
    gdcc_bind_method${helper.renderFuncBindName(function)}(class_name, GD_STATIC_SN(u8"${function.name}"), ${classDef.name}_${function.name}<#if function.parameters?size gt function.static?then(0, 1)>,<#else>);</#if>
            <#list function.parameters as parameter>
                <#if parameter.name != "self">
                    GD_STATIC_SN(u8"${parameter.name}"), GDEXTENSION_VARIANT_TYPE_${parameter.type.gdExtensionType.name()}<#if parameter_has_next>,<#else>);</#if>
                </#if>
            </#list>
        </#if>
    </#list>
    // Properties
    <#list classDef.properties as property>
    {
        <#if !property.static>
            <#assign propertyMetadata = helper.renderPropertyMetadata(property)>
<#--            gdcc_bind_method${helper.renderGetterBindName(property)}(class_name, GD_STATIC_SN(u8"${property.getterFunc}"), ${classDef.name}_${property.getterFunc});-->
<#--            gdcc_bind_method${helper.renderSetterBindName(property)}(class_name, GD_STATIC_SN(u8"${property.setterFunc}"), ${classDef.name}_${property.setterFunc}, GD_STATIC_SN(u8"value"), GDEXTENSION_VARIANT_TYPE_${property.type.gdExtensionType.name()});-->
            <#-- Property outward metadata stays centralized in renderPropertyMetadata(...): it owns type/hint/hint_string/usage
                 for Variant, typed Array and typed Dictionary alike, while property class_name still uses the current owner-class slot. -->
            gdcc_bind_property_full(class_name, GD_STATIC_SN(u8"${property.name}"), ${propertyMetadata.typeEnumLiteral}, ${propertyMetadata.hintEnumLiteral}, ${propertyMetadata.hintStringExpr}, class_name, ${propertyMetadata.usageExpr}, GD_STATIC_SN(u8"${property.getterFunc}"), GD_STATIC_SN(u8"${property.setterFunc}"));
        </#if>
    }
    </#list>
}
</#list>

// Object pointer helpers for GDCC wrapper layout
<#list module.classDefs as classDef>
static inline GDExtensionObjectPtr ${classDef.name}_object_ptr(${classDef.name}* self) {
    if (self == NULL) {
        return NULL;
    }
    <#if helper.checkGdccClassByName(classDef.superName)>
        return ${classDef.superName}_object_ptr(&self->_super);
    <#else>
        return self->_object;
    </#if>
}

static inline void ${classDef.name}_set_object_ptr(${classDef.name}* self, GDExtensionObjectPtr obj) {
    if (self == NULL) {
        return;
    }
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName}_set_object_ptr(&self->_super, obj);
    <#else>
        self->_object = obj;
    </#if>
}
</#list>

// GdExtension Methods for each class
<#list module.classDefs as classDef>
<#list classDef.properties as property>
static inline void ${helper.renderPropertyInitApplyHelperName(classDef, property)}(${classDef.name}* self) {
    ${bodyRender.generatePropertyInitApplyBody(classDef, property)}
}
</#list>

GDExtensionObjectPtr ${classDef.name}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize) {
    GDExtensionObjectPtr obj = godot_classdb_construct_object2(GD_STATIC_SN(u8"${helper.resolveNearestNativeAncestorName(classDef)}"));
    ${classDef.name}* self = godot_mem_alloc(sizeof(${classDef.name}));
    ${classDef.name}_set_object_ptr(self, obj);
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
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName}_class_constructor(&self->_super);
    </#if>
    <#list classDef.properties as property>
        ${helper.renderPropertyInitApplyHelperName(classDef, property)}(self);
    </#list>
    <#list classDef.functions as function>
        <#if function.name == "_init" && !function.static && function.parameters?size == 1>
            ${classDef.name}__init(self);
        </#if>
    </#list>
}

void ${classDef.name}_class_destructor(${classDef.name}* self) {
    if (self == NULL) {
        return;
    }
    <#list classDef.properties as property>
        <#if property.type.destroyable>
            <#if property.type.gdExtensionType.name() == "OBJECT">
                <#if helper.checkGdccType(property.type)>
                    try_release_object(${helper.renderGdTypeName(property.type)}_object_ptr(self->${property.name}));
                <#else>
                    try_release_object(self->${property.name});
                </#if>
            <#else>
                ${helper.renderDestroyFunctionName(property.type)}(&(self->${property.name}));
            </#if>
        </#if>
    </#list>
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName}_class_destructor(&self->_super);
    </#if>
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
    (void)p_class_userdata;
    (void)p_hash;
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
    (void)p_name;
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
    ${bodyRender.generateFuncBody(classDef, func)}
}
</#list>

</#list>
