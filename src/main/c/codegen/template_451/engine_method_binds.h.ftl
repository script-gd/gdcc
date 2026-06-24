<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="gd.script.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="usedEngineMethods" type="java.util.List<gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver.ResolvedMethodCall>" -->
<#-- @ftlvariable name="usedEngineConstructors" type="java.util.List<gd.script.gdcc.backend.c.gen.binding.usage.EngineConstructorUsage>" -->
<#-- @ftlvariable name="usedModuleLocalBindings" type="java.util.List<gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding>" -->
#ifndef GDEXTENSION_${module.moduleName?upper_case}_ENGINE_METHOD_BINDS_H
#define GDEXTENSION_${module.moduleName?upper_case}_ENGINE_METHOD_BINDS_H

// Engine constructor wrappers used by this module.
<#if usedEngineConstructors?size == 0>
// No engine constructors were collected for this module.
<#else>
<#list usedEngineConstructors as constructor>
static inline godot_${constructor.cIdentifier} *godot_new_${constructor.cIdentifier}(void) {
    GDExtensionObjectPtr object = godot_classdb_construct_object(GD_STATIC_SN(u8"${constructor.escapedClassName}"));
    if (object == NULL) {
        gdcc_binding_lookup_context context = { 0 };
        context.kind = "engine_constructor";
        context.function_name = "godot_new_${constructor.cIdentifier}";
        context.lookup_name = "${constructor.escapedClassName}";
        context.owner = "${constructor.escapedClassName}";
        context.type = "${constructor.escapedClassName}";
        gdcc_binding_lookup_fail(&context);
        return NULL;
    }
    return (godot_${constructor.cIdentifier} *)object;
}

</#list>
</#if>

// Module-local Godot wrappers used by this module.
<#if usedModuleLocalBindings?size == 0>
// No module-local Godot wrappers were collected for this module.
<#else>
<#list usedModuleLocalBindings as binding>
<#if binding.familyName() == "SINGLETON">
<#-- @ftlvariable name="binding" type="gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding.Singleton" -->
static inline ${binding.returnType()} ${binding.cFunctionName()}(void) {
    static ${binding.returnType()} ${binding.cacheName()} = NULL;
    if (${binding.cacheName()} == NULL) {
        ${binding.cacheName()} = (${binding.returnType()})godot_global_get_singleton(GD_STATIC_SN(u8"${binding.escapedLookupName()}"));
        if (${binding.cacheName()} == NULL) {
            gdcc_binding_lookup_context context = { 0 };
            context.kind = "module_singleton";
            context.function_name = "${binding.escapedCFunctionName()}";
            context.lookup_name = "${binding.escapedLookupName()}";
            context.owner = "${binding.escapedOwner()}";
            context.type = "${binding.escapedReturnTypeName()}";
            gdcc_binding_lookup_fail(&context);
            return NULL;
        }
    }
    return ${binding.cacheName()};
}

<#elseif binding.familyName() == "CLASS_CONSTANT">
<#-- @ftlvariable name="binding" type="gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding.ClassConstant" -->
static inline godot_int ${binding.cFunctionName()}(void) {
    return (godot_int)${binding.constantValue()};
}

<#else>
<#stop "Unsupported module-local Godot binding family: ${binding.familyName()}">
</#if>
</#list>
</#if>

// Exact engine method-bind accessors used by this module.
// The session snapshot order matches the first successful entry.c body render hit order.
<#if usedEngineMethods?size == 0>
// No exact engine method binds were collected for this module.
<#else>
<#list usedEngineMethods as resolved>
<#assign helperParams = helper.collectEngineMethodHelperParameters(resolved)>
<#assign lookupHashes = helper.collectEngineMethodBindLookupHashes(resolved)>
GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(
        ${helper.renderEngineMethodBindAccessorName(resolved)},
        u8"${resolved.ownerClassName}",
        u8"${resolved.methodName}",
        "${resolved.ownerClassName}",
        "${resolved.methodName}",
        (GDExtensionInt)${lookupHashes[0]?c}LL,
        (GDExtensionInt)${lookupHashes?size - 1},
<#if lookupHashes?size gt 1>
<#list lookupHashes as hash>
    <#if hash_index gt 0>    (GDExtensionInt)${hash?c}LL<#if hash_has_next>,</#if></#if>
</#list>
<#else>
        (GDExtensionInt)0
</#if>
)

// Direct exact-engine helper kept separate from public Godot wrappers.
static inline ${helper.renderGdTypeInC(resolved.returnType)} ${helper.renderEngineMethodCallHelperName(resolved)}(
<#if !resolved.isStatic() || helperParams?size gt 0 || resolved.isVararg()>
<#if !resolved.isStatic()>
    GDExtensionObjectPtr self<#if helperParams?size gt 0 || resolved.isVararg()>,</#if>
</#if>
<#list helperParams as param>
    ${param.cType} ${param.name}<#if param_has_next || resolved.isVararg()>,</#if>
</#list>
<#if resolved.isVararg()>
    const godot_Variant **argv,
    godot_int argc
</#if>
<#else>
    void
</#if>
) {
    GDExtensionMethodBindPtr bind = NULL;
    if (!${helper.renderEngineMethodBindAccessorName(resolved)}(&bind)) {
<#if resolved.returnType.typeName == "void">
        return;
<#else>
        return ${helper.renderDefaultValueExprInC(resolved.returnType)};
</#if>
    }
<#if resolved.isVararg()>
<#list helperParams as param>
    godot_Variant fixed_arg_${param_index} = ${helper.renderPackFunctionName(param.type)}(${helper.renderEngineMethodHelperValueExpr(param)});
</#list>
<#if helperParams?size gt 0>
    const GDExtensionConstVariantPtr fixed_args[] = {
<#list helperParams as param>
        &fixed_arg_${param_index}<#if param_has_next>,</#if>
</#list>
    };
    const godot_int fixed_argc = (godot_int)${helperParams?size};
    const godot_int final_argc = fixed_argc + argc;
    GDExtensionConstVariantPtr final_args[${helperParams?size} + argc];
    for (godot_int i = 0; i < fixed_argc; ++i) {
        final_args[i] = fixed_args[i];
    }
    for (godot_int i = 0; i < argc; ++i) {
        final_args[fixed_argc + i] = argv[i];
    }
    const GDExtensionConstVariantPtr *call_args = final_args;
<#else>
    const godot_int final_argc = argc;
    const GDExtensionConstVariantPtr *call_args = argc > 0 ? (const GDExtensionConstVariantPtr *)argv : NULL;
</#if>
    GDExtensionCallError error = { 0 };
    // object_method_bind_call constructs into raw Variant storage; error paths must not destroy it.
    godot_bool ret_initialized = false;
    godot_Variant ret;
<#if resolved.returnType.typeName != "void">
    godot_bool call_ok = false;
    ${helper.renderGdTypeInC(resolved.returnType)} result;
</#if>
    godot_object_method_bind_call(
        bind,
<#if resolved.isStatic()>
        NULL,
<#else>
        self,
</#if>
        call_args,
        final_argc,
        (GDExtensionUninitializedVariantPtr)&ret,
        &error
    );
    if (error.error != GDEXTENSION_CALL_OK) {
        char call_error_desc[512];
        switch (error.error) {
            case GDEXTENSION_CALL_ERROR_INVALID_METHOD:
<#if resolved.ownerClassName == "Object" && resolved.methodName == "call" && helperParams?size gt 0>
            {
                char target_method_name[256];
                gdcc_string_name_to_utf8(${helperParams[0].name}, target_method_name, sizeof(target_method_name));
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: invalid target method '%s'",
                    target_method_name
                );
                break;
            }
<#else>
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: invalid method"
                );
                break;
</#if>
            case GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT:
            {
                char expected_type_name[64];
                char actual_type_name[64];
                gdcc_variant_type_to_utf8(error.expected, expected_type_name, sizeof(expected_type_name));
                if (call_args != NULL && error.argument >= 0 && error.argument < final_argc && call_args[error.argument] != NULL) {
                    gdcc_variant_type_to_utf8(
                        godot_variant_get_type((const godot_Variant *)call_args[error.argument]),
                        actual_type_name,
                        sizeof(actual_type_name)
                    );
                } else {
                    snprintf(actual_type_name, sizeof(actual_type_name), "<unknown>");
                }
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: invalid argument #%lld, expected '%s', got '%s'",
                    (long long)error.argument,
                    expected_type_name,
                    actual_type_name
                );
                break;
            }
            case GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS:
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: too many arguments, expected %lld, got %lld",
                    (long long)error.expected,
                    (long long)final_argc
                );
                break;
            case GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS:
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: too few arguments, expected %lld, got %lld",
                    (long long)error.expected,
                    (long long)final_argc
                );
                break;
            case GDEXTENSION_CALL_ERROR_INSTANCE_IS_NULL:
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: instance is null"
                );
                break;
            case GDEXTENSION_CALL_ERROR_METHOD_NOT_CONST:
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: method is not const"
                );
                break;
            default:
                snprintf(
                    call_error_desc,
                    sizeof(call_error_desc),
                    "engine method call failed: ${resolved.ownerClassName}.${resolved.methodName}: unknown call error %d",
                    (int)error.error
                );
                break;
        }
        GDCC_PRINT_RUNTIME_ERROR(call_error_desc, __func__, __FILE__, __LINE__);
        goto cleanup;
    }
    ret_initialized = true;
<#if resolved.returnType.typeName != "void">
    result = ${helper.renderUnpackFunctionName(resolved.returnType)}((GDExtensionVariantPtr)&ret);
    call_ok = true;
</#if>
cleanup:
    if (ret_initialized) {
        godot_Variant_destroy(&ret);
    }
<#list helperParams?reverse as param>
    godot_Variant_destroy(&fixed_arg_${helperParams?size - param_index - 1});
</#list>
<#if resolved.returnType.typeName == "void">
    return;
<#else>
    if (!call_ok) {
        return ${helper.renderDefaultValueExprInC(resolved.returnType)};
    }
    return result;
</#if>
<#else>
<#list helperParams as param>
<#if helper.checkEngineMethodHelperRequiresLocalValueSlot(param)>
    ${helper.renderEngineMethodHelperLocalSlotDecl(param)}
</#if>
</#list>
<#if helperParams?size gt 0>
    const GDExtensionConstTypePtr args[] = {
<#list helperParams as param>
        ${helper.renderEngineMethodPtrcallSlotExpr(param)}<#if param_has_next>,</#if>
</#list>
    };
</#if>
<#if resolved.returnType.typeName == "void">
    godot_object_method_bind_ptrcall(
        bind,
<#if resolved.isStatic()>
        NULL,
<#else>
        self,
</#if>
<#if helperParams?size gt 0>
        args,
<#else>
        NULL,
</#if>
        NULL
    );
    return;
<#else>
    ${helper.renderGdTypeInC(resolved.returnType)} result = { 0 };
    godot_object_method_bind_ptrcall(
        bind,
<#if resolved.isStatic()>
        NULL,
<#else>
        self,
</#if>
<#if helperParams?size gt 0>
        args,
<#else>
        NULL,
</#if>
        &result
    );
    return result;
</#if>
</#if>
}

</#list>
</#if>
#endif // GDEXTENSION_${module.moduleName?upper_case}_ENGINE_METHOD_BINDS_H
