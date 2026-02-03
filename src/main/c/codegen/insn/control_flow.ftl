<#-- @ftlvariable name="insn" type="dev.superice.gdcc.lir.insn.ControlFlowInstruction" -->
<#-- @ftlvariable name="func" type="dev.superice.gdcc.lir.LirFunctionDef" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
<#include "trim.ftl">
<#switch insn.opcode().opcode()>
    <#case "goto">
        <#assign gotoInsn = insn.asGotoInsn>
        <@t/>goto ${gotoInsn.targetBbId};
        <#break>
    <#case "go_if">
        <#assign goIfInsn = insn.asGoIfInsn>
        <@t/>if ($${goIfInsn.conditionVarId}) {
        <@t/>    goto ${goIfInsn.trueBbId};
        <@t/>} else {
        <@t/>    goto ${goIfInsn.falseBbId};
        <@t/>}
        <#break>
    <#case "return">
        <#assign returnInsn = insn.asReturnInsn>
        <#if returnInsn.returnValueId??>
            <@t/>return $${returnInsn.returnValueId};
        <#else>
            <@t/>return;
        </#if>
        <#break>
</#switch>