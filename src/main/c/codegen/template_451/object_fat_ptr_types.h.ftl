<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="objectFatPtrSpecs" type="java.util.List<gd.script.gdcc.backend.c.gen.ObjectFatPtrSpec>" -->
#ifndef GDEXTENSION_${module.moduleName?upper_case}_OBJECT_FAT_PTR_TYPES_H
#define GDEXTENSION_${module.moduleName?upper_case}_OBJECT_FAT_PTR_TYPES_H

#include <godot_binding.h>

<#list objectFatPtrSpecs as spec>
<#if spec.kind.name() == "GDCC">
typedef struct ${spec.canonicalClassName} ${spec.canonicalClassName};
</#if>
</#list>
<#if objectFatPtrSpecs?size gt 0>
// Object fat pointer declarations

<#list objectFatPtrSpecs as spec>
typedef struct ${spec.fatPtrTypeName} {
    ${spec.pointerCType}ptr;
    GDObjectInstanceID instance_id;
} ${spec.fatPtrTypeName};

</#list>
</#if>
#endif
