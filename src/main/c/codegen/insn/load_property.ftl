<#-- @ftlvariable name="insn" type="dev.superice.gdcc.lir.insn.LoadPropertyInsn" -->
<#-- @ftlvariable name="func" type="dev.superice.gdcc.lir.LirFunctionDef" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="gen" type="dev.superice.gdcc.backend.c.gen.insn.LoadPropertyInsnGen" -->
<#-- @ftlvariable name="genMode" type="java.lang.String" -->
<#-- @ftlvariable name="gettingSelf" type="java.lang.Boolean" -->
<#-- @ftlvariable name="gdccGetterName" type="java.lang.String" -->
<#include "trim.ftl">
<#assign resultType = func.getVariableById(insn.resultId).type>
<#assign objectType = func.getVariableById(insn.objectId).type>
<#switch genMode>
    <#case "gdcc">
    <#--  If we are in the getter, then do not call the getter recursively  -->
        <#if gettingSelf>
            <@t/>$${insn.resultId} = ${helper.renderCopyAssignFunctionName(resultType)}(${helper.renderValueRef(resultType, "($${insn.objectId}->${insn.propertyName})")});
        <#else>
            <@t/>$${insn.resultId} = ${resultType.typeName}_${gdccGetterName}($${insn.objectId});
        </#if>
        <#break>
    <#case "engine">
        <@t/>$${insn.resultId} = godot_${objectType.typeName}_get_${insn.propertyName}($${insn.objectId});
        <#break>
    <#case "general">
        <@t/>{
        <#if helper.checkGdccType(objectType)>
        <@t/>    godot_Variant __temp_variant = godot_Object_get($${insn.objectId}->_object, GD_STATIC_SN(u8"${insn.propertyName}"));
        <#else>
        <@t/>    godot_Variant __temp_variant = godot_Object_get($${insn.objectId}, GD_STATIC_SN(u8"${insn.propertyName}"))
        </#if>
        <@t/>    $${insn.resultId} = ${helper.renderUnpackFunctionName(resultType)}(&__temp_variant);
        <@t/>}
        <#break>
    <#case "builtin">
        <#if func.checkVariableRef(insn.objectId)>
            <@t/>$${insn.resultId} = godot_${resultType.typeName}_get_${insn.propertyName}($${insn.objectId});
        <#else>
            <@t/>$${insn.resultId} = godot_${resultType.typeName}_get_${insn.propertyName}(&$${insn.objectId});
        </#if>
        <#break>
</#switch>