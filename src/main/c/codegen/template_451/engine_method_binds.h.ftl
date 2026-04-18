<#-- @ftlvariable name="module" type="dev.superice.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="dev.superice.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="usedEngineMethods" type="java.util.List<dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver.ResolvedMethodCall>" -->
#ifndef GDEXTENSION_${module.moduleName?upper_case}_ENGINE_METHOD_BINDS_H
#define GDEXTENSION_${module.moduleName?upper_case}_ENGINE_METHOD_BINDS_H

// Exact engine method-bind accessors used by this module.
// The session snapshot order matches the first successful entry.c body render hit order.
<#if usedEngineMethods?size == 0>
// No exact engine method binds were collected for this module.
<#else>
<#list usedEngineMethods as resolved>
static inline GDExtensionMethodBindPtr ${helper.renderEngineMethodBindAccessorName(resolved)}(void) {
    static GDExtensionMethodBindPtr bind = NULL;
    if (bind != NULL) {
        return bind;
    }
    <#list helper.collectEngineMethodBindLookupHashes(resolved) as hash>
    bind = godot_classdb_get_method_bind(
        GD_STATIC_SN(u8"${resolved.ownerClassName}"),
        GD_STATIC_SN(u8"${resolved.methodName}"),
        (GDExtensionInt)${hash?c}LL
    );
    if (bind != NULL) {
        return bind;
    }
    </#list>
    return NULL;
}

</#list>
</#if>
#endif // GDEXTENSION_${module.moduleName?upper_case}_ENGINE_METHOD_BINDS_H
