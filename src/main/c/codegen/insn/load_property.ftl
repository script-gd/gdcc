<#-- @ftlvariable name="insn" type="dev.superice.gdcc.lir.insn.LoadPropertyInsn" -->
<#-- @ftlvariable name="func" type="dev.superice.gdcc.lir.LirFunctionDef" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="gen" type="dev.superice.gdcc.backend.c.gen.insn.LoadPropertyInsnGen" -->
<#-- @ftlvariable name="genMode" type="java.lang.String" -->
<#-- @ftlvariable name="gettingSelf" type="java.lang.Boolean" -->
<#-- @ftlvariable name="gdccGetterName" type="java.lang.String" -->
<#-- @ftlvariable name="propertyType" type="dev.superice.gdcc.type.GdType" -->
<#assign resultType = func.getVariableById(insn.resultId).type>
<#assign objectType = func.getVariableById(insn.objectId).type>
<#switch genMode>
    <#case "gdcc">
    <#--  If we are in the getter, then do not call the getter recursively  -->
        <#if gettingSelf>
            $${insn.resultId} = ${helper.renderCopyAssignFunctionName(resultType)}(${helper.renderValueRef(resultType, "($${insn.objectId}->${insn.propertyName})")});
        <#else>
            $${insn.resultId} = ${resultType.typeName}_${gdccGetterName}($${insn.objectId});
        </#if>
        <#break>
    <#case "engine">
        ${helper.renderVarAssignWithGodotReturn(
            func, insn.resultId, propertyType,
            "godot_${objectType.typeName}_get_${insn.propertyName}($${insn.objectId})"
        )}
        <#break>
    <#case "general">
        {
        <#if helper.checkGdccType(objectType)>
            godot_Variant __temp_variant = godot_Object_get($${insn.objectId}->_object, GD_STATIC_SN(u8"${insn.propertyName}"));
        <#else>
            godot_Variant __temp_variant = godot_Object_get($${insn.objectId}, GD_STATIC_SN(u8"${insn.propertyName}"))
        </#if>
            ${helper.renderVarAssignWithGodotReturn(
                func, insn.resultId, resultType,
                "${helper.renderUnpackFunctionName(resultType)}(&__temp_variant)"
            )}
        }
        <#break>
    <#case "builtin">
        <#if func.checkVariableRef(insn.objectId)>
            ${helper.renderVarAssignWithGodotReturn(
                func, insn.resultId, propertyType,
                "godot_${resultType.typeName}_get_${insn.propertyName}($${insn.objectId})"
            )}
        <#else>
            ${helper.renderVarAssignWithGodotReturn(
                func, insn.resultId, propertyType,
                "godot_${resultType.typeName}_get_${insn.propertyName}(&$${insn.objectId})"
            )}
        </#if>
        <#break>
</#switch>