<#macro lambdaCaptureName classDef func>
${classDef.name}_Capture_${func.name}
</#macro>
<#macro funcHeader helper classDef func>
<#-- @ftlvariable name="func" type="dev.superice.gdcc.scope.FunctionDef" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
${helper.renderGdTypeInC(func.returnType)} ${classDef.name}_${func.name}(
    <#list func.parameters as param>
        ${helper.renderGdTypeRefInC(param.type)} $${param.name}<#if param_has_next || func.captureCount gt 0>,</#if>
    </#list>
    <#list func.captureList as capture>
        <@lambdaCaptureName classDef func/>* _capture
    </#list>
)
</#macro>