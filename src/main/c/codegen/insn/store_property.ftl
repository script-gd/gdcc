<#-- @ftlvariable name="insn" type="dev.superice.gdcc.lir.insn.StorePropertyInsn" -->
<#-- @ftlvariable name="func" type="dev.superice.gdcc.lir.LirFunctionDef" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="gen" type="dev.superice.gdcc.backend.c.gen.insn.StorePropertyInsnGen" -->
<#-- @ftlvariable name="genMode" type="java.lang.String" -->
<#-- @ftlvariable name="insideSelfSetter" type="java.lang.Boolean" -->
<#-- @ftlvariable name="gdccSetterName" type="java.lang.String" -->
<#include "trim.ftl">
<#assign valueType = func.getVariableById(insn.valueId).type>
<#assign objectType = func.getVariableById(insn.objectId).type>

<#switch genMode>
    <#case "gdcc">
    <#-- If we are inside the setter for this field itself, assign the backing field directly to avoid recursion -->
        <#if insideSelfSetter>
            <@t/>$${insn.objectId}->${insn.propertyName} = ${helper.renderCopyAssignFunctionName(valueType)}(${helper.renderVarRef(func, insn.valueId)});
        <#else>
            <@t/>${valueType.typeName}_${gdccSetterName}($${insn.objectId}, ${helper.renderVarRef(func, insn.valueId)});
        </#if>
        <#break>
    <#case "engine">
        <@t/>godot_${objectType.typeName}_set_${insn.propertyName}($${insn.objectId}, ${helper.renderVarRef(func, insn.valueId)});
        <#break>
    <#case "general">
        <@t/>{
        <@t/>    godot_Variant __temp_variant = ${helper.renderPackFunctionName(valueType)}(${helper.renderVarRef(func, insn.valueId)});
        <#if helper.checkGdccType(objectType)>
        <@t/>    godot_Object_set($${insn.objectId}->_object, GD_STATIC_SN(u8"${insn.propertyName}"), &__temp_variant);
        <#else>
        <@t/>    godot_Object_set($${insn.objectId}, GD_STATIC_SN(u8"${insn.propertyName}"), &__temp_variant);
        </#if>
        <@t/>}
        <#break>
    <#case "builtin">
        <#if func.checkVariableRef(insn.objectId)>
            <@t/>godot_${valueType.typeName}_set_${insn.propertyName}($${insn.objectId}, ${helper.renderVarRef(func, insn.valueId)});
        <#else>
            <@t/>godot_${valueType.typeName}_set_${insn.propertyName}(&$${insn.objectId}, ${helper.renderVarRef(func, insn.valueId)});
        </#if>
        <#break>
</#switch>
