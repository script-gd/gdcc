<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="gd.script.gdcc.backend.c.gen.CGenHelper" -->
<#include "trim.ftl">
<#include "func.ftl">
#ifndef GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H
#define GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H

#include <godot_binding.h>
static GDExtensionClassLibraryPtr class_library = NULL;
#include <gdcc_helper.h>

struct GDExtensionInitializationStatus {
    godot_bool initialized;
};

void initialize(void* userdata, GDExtensionInitializationLevel p_level);
void deinitialize(void* userdata, GDExtensionInitializationLevel p_level);

// Class declarations

<#list module.classDefs as classDef>
typedef struct ${classDef.name} ${classDef.name};
</#list>

#include "object_fat_ptr_types.h"

<#list module.classDefs as classDef>
// Class definition for ${classDef.name}

struct ${classDef.name} {
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName} _super;
    <#else>
        GDExtensionObjectPtr _object;
    </#if>
    <#list classDef.properties as property>
        <#if !property.static>
            ${helper.renderGdTypeInC(property.type)} ${property.name};
        </#if>
    </#list>
};

static inline GDExtensionObjectPtr ${classDef.name}_object_ptr(${classDef.name}* self);
static inline void ${classDef.name}_set_object_ptr(${classDef.name}* self, GDExtensionObjectPtr obj);

const GDExtensionInstanceBindingCallbacks ${classDef.name}_class_binding_callbacks = {
    NULL,
    NULL,
    NULL,
};

static void ${classDef.name}_class_bind_methods();

GDExtensionObjectPtr ${classDef.name}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize);

void ${classDef.name}_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance);

void ${classDef.name}_class_constructor(${classDef.name}* self);

void ${classDef.name}_class_destructor(${classDef.name}* self);

void ${classDef.name}_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed);

void* ${classDef.name}_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name, uint32_t p_hash);

void ${classDef.name}_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance, GDExtensionConstStringNamePtr p_name, void* p_virtual_call_userdata, const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_ret);

// Methods for ${classDef.name}

<#list classDef.functions as func>
<#-- Lambda function -->
<#if func.lambda>
typedef struct <@lambdaCaptureName classDef func/> {
<#list func.captureList as capture>
    ${helper.renderGdTypeRefInC(capture.type)} ${capture.name};
</#list>
} <@lambdaCaptureName classDef func/>;
</#if>
<#-- Normal function -->
<@funcHeader helper classDef func/>;
</#list>

</#list>

<#assign operatorEvaluatorHelperSpecs = helper.collectOperatorEvaluatorHelperSpecs(module)>
<#if operatorEvaluatorHelperSpecs?size gt 0>
// Operator evaluator helpers
// Internal signatures use fat-pointer object values; Godot's evaluator ABI still takes raw object pointer slots.
<#list operatorEvaluatorHelperSpecs as spec>
static inline ${helper.renderOperatorEvaluatorHelperReturnTypeInC(spec.returnType)} ${spec.functionName}(
    ${helper.renderOperatorEvaluatorHelperTypeInC(spec.leftType)} left<#if !spec.unary>,
    ${helper.renderOperatorEvaluatorHelperTypeInC(spec.rightType)} right</#if>
) {
    static GDExtensionPtrOperatorEvaluator evaluator = NULL;
    if (evaluator == NULL) {
        evaluator = godot_variant_get_ptr_operator_evaluator(
            ${spec.operatorEnumLiteral},
            ${spec.leftVariantTypeEnumLiteral},
            <#if spec.unary>GDEXTENSION_VARIANT_TYPE_NIL<#else>${spec.rightVariantTypeEnumLiteral}</#if>
        );
        if (evaluator == NULL) {
            GDCC_PRINT_RUNTIME_ERROR("operator evaluator is unavailable: ${spec.functionName}", __func__, __FILE__, __LINE__);
            return ${helper.renderDefaultValueExprInC(spec.returnType)};
        }
    }
    ${helper.renderOperatorEvaluatorObjectRawSlotDecl(spec.leftType, "left")}<#if !spec.unary>${helper.renderOperatorEvaluatorObjectRawSlotDecl(spec.rightType, "right")}</#if>
    // Operator evaluators assign into an existing carrier; destroyable returns must start initialized.
    // Object returns (if any) use a raw carrier then ownership-neutral _from_raw. Live production paths
    // today only need object operands (e.g. String in Object); object return is defensive for metadata.
    ${helper.renderOperatorEvaluatorResultCarrierTypeInC(spec.returnType)} result = { 0 };
    evaluator(
        ${helper.renderOperatorEvaluatorArgExpr(spec.leftType, "left")},
        <#if spec.unary>NULL<#else>${helper.renderOperatorEvaluatorArgExpr(spec.rightType, "right")}</#if>,
        &result
    );
    return ${helper.renderOperatorEvaluatorReturnExpr(spec.returnType, "result")};
}
</#list>
</#if>

#include "engine_method_binds.h"

// Method binding helpers

<#list helper.bindingDataList as bindingData>
static void call${helper.renderFuncBindName(bindingData)}(
    void* method_userdata,
    GDExtensionClassInstancePtr p_instance, const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
    GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
<#--     Check argument count-->
    if (p_argument_count < ${bindingData.paramTypes?size}) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
        r_error->expected = ${bindingData.paramTypes?size};
        return;
    }
    if (p_argument_count > ${bindingData.paramTypes?size}) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = ${bindingData.paramTypes?size};
        return;
    }

<#--Check the argument type. -->
<#--Variant outward slots are encoded as NIL metadata, so only non-Variant -->
<#--parameters keep the runtime gate here. Non-exact inbound exceptions must be -->
<#--accepted by this gate before wrapper-local materialization runs below. -->
    <#list bindingData.paramTypes as paramType>
    <#if paramType.typeName != "Variant">
    const GDExtensionVariantType arg${paramType_index}_type = godot_variant_get_type(p_args[${paramType_index}]);
    if (!${helper.renderCallWrapperVariantTypeGate(paramType, "arg${paramType_index}_type")}) {
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
        r_error->expected = GDEXTENSION_VARIANT_TYPE_${paramType.gdExtensionType.name()};
        r_error->argument = ${paramType_index};
        return;
    }
    </#if>
    </#list>

<#--Typed-container preflight stays ahead of wrapper-local unpack/materialization so -->
<#--mismatches can return without introducing a second cleanup contract for partially -->
<#--materialized locals.-->
    <#list bindingData.paramTypes as paramType>
        <#if helper.needsTypedArrayCallGuard(paramType)>
        <#assign probeVarName = "probe" + paramType_index>
        <#assign expectedBuiltinType = helper.renderTypedArrayGuardBuiltinTypeLiteral(paramType)>
        {
            // Compare compile-time known typed-array metadata directly to avoid extra
            // is_same_typed(...) overhead on the wrapper hot path.
            godot_Array ${probeVarName} = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[${paramType_index}]);
            godot_bool typed_mismatch = godot_Array_get_typed_builtin(&${probeVarName}) != ${expectedBuiltinType};
            <#if helper.isTypedArrayGuardObjectLeaf(paramType)>
                <#assign expectedClassNameExpr = helper.renderTypedArrayGuardClassNameExpr(paramType)>
            if (!typed_mismatch) {
                godot_StringName ${probeVarName}_class_name = godot_Array_get_typed_class_name(&${probeVarName});
                godot_Variant ${probeVarName}_script = godot_Array_get_typed_script(&${probeVarName});
                godot_Variant ${probeVarName}_script_nil = godot_new_Variant_nil();
                godot_Variant ${probeVarName}_script_is_null_result;
                godot_bool ${probeVarName}_script_is_null_valid = false;
                // Godot reports absent script leaf metadata as OBJECT/null; evaluate constructs into raw result storage.
                godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &${probeVarName}_script, &${probeVarName}_script_nil, (GDExtensionUninitializedVariantPtr)&${probeVarName}_script_is_null_result, &${probeVarName}_script_is_null_valid);
                const godot_bool ${probeVarName}_script_is_null = ${probeVarName}_script_is_null_valid && godot_new_bool_with_Variant(&${probeVarName}_script_is_null_result);
                typed_mismatch = !godot_StringName_op_equal_StringName(&${probeVarName}_class_name, ${expectedClassNameExpr}) || !${probeVarName}_script_is_null;
                if (${probeVarName}_script_is_null_valid) {
                    godot_Variant_destroy(&${probeVarName}_script_is_null_result);
                }
                godot_Variant_destroy(&${probeVarName}_script_nil);
                godot_Variant_destroy(&${probeVarName}_script);
                godot_StringName_destroy(&${probeVarName}_class_name);
            }
            </#if>
            godot_Array_destroy(&${probeVarName});
            if (typed_mismatch) {
                r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
                r_error->expected = GDEXTENSION_VARIANT_TYPE_ARRAY;
                r_error->argument = ${paramType_index};
                return;
            }
        }
        </#if>
    </#list>

    <#list bindingData.paramTypes as paramType>
        <#if helper.needsTypedDictionaryCallGuard(paramType)>
        <#assign probeVarName = "probe" + paramType_index>
        {
            // Typed Dictionary slots need a second-stage typedness check before wrapper locals exist.
            godot_Dictionary ${probeVarName} = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[${paramType_index}]);
            godot_bool typed_mismatch = false;
            <#list ["key", "value"] as typedSide>
                <#assign expectedBuiltinType = helper.renderTypedDictionaryGuardBuiltinTypeLiteral(paramType, typedSide)>
            if (!typed_mismatch) {
                typed_mismatch = godot_Dictionary_get_typed_${typedSide}_builtin(&${probeVarName}) != ${expectedBuiltinType};
            }
                <#if helper.isTypedDictionaryGuardObjectLeaf(paramType, typedSide)>
                    <#assign expectedClassNameExpr = helper.renderTypedDictionaryGuardClassNameExpr(paramType, typedSide)>
            if (!typed_mismatch) {
                godot_StringName ${probeVarName}_${typedSide}_class_name = godot_Dictionary_get_typed_${typedSide}_class_name(&${probeVarName});
                godot_Variant ${probeVarName}_${typedSide}_script = godot_Dictionary_get_typed_${typedSide}_script(&${probeVarName});
                godot_Variant ${probeVarName}_${typedSide}_script_nil = godot_new_Variant_nil();
                godot_Variant ${probeVarName}_${typedSide}_script_is_null_result;
                godot_bool ${probeVarName}_${typedSide}_script_is_null_valid = false;
                // Godot reports absent script leaf metadata as OBJECT/null; evaluate constructs into raw result storage.
                godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &${probeVarName}_${typedSide}_script, &${probeVarName}_${typedSide}_script_nil, (GDExtensionUninitializedVariantPtr)&${probeVarName}_${typedSide}_script_is_null_result, &${probeVarName}_${typedSide}_script_is_null_valid);
                const godot_bool ${probeVarName}_${typedSide}_script_is_null = ${probeVarName}_${typedSide}_script_is_null_valid && godot_new_bool_with_Variant(&${probeVarName}_${typedSide}_script_is_null_result);
                typed_mismatch = !godot_StringName_op_equal_StringName(&${probeVarName}_${typedSide}_class_name, ${expectedClassNameExpr}) || !${probeVarName}_${typedSide}_script_is_null;
                if (${probeVarName}_${typedSide}_script_is_null_valid) {
                    godot_Variant_destroy(&${probeVarName}_${typedSide}_script_is_null_result);
                }
                godot_Variant_destroy(&${probeVarName}_${typedSide}_script_nil);
                godot_Variant_destroy(&${probeVarName}_${typedSide}_script);
                godot_StringName_destroy(&${probeVarName}_${typedSide}_class_name);
            }
                </#if>
            </#list>
            godot_Dictionary_destroy(&${probeVarName});
            if (typed_mismatch) {
                r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
                r_error->expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY;
                r_error->argument = ${paramType_index};
                return;
            }
        }
        </#if>
    </#list>

    // Extract the argument. Wrapper-owned non-object locals stay mutable so the
    // cleanup epilogue below can destroy them before returning to Godot.
    <#list bindingData.paramTypes as paramType>
        <#assign argCleanupStmt = helper.renderCallWrapperDestroyStmt(paramType, "arg${paramType_index}")>
        <#if argCleanupStmt?has_content>
        ${helper.renderGdTypeInC(paramType)} arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType, "(GDExtensionVariantPtr)p_args[${paramType_index}]", "arg${paramType_index}_type")};
        <#else>
        const ${helper.renderGdTypeInC(paramType)} arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType, "(GDExtensionVariantPtr)p_args[${paramType_index}]", "arg${paramType_index}_type")};
        </#if>
    </#list>

    // Call the function. Instance methods receive owner fat self; static methods omit self.
    // Wrapper-local non-object values materialized above must be destroyed here.
<#if bindingData.staticMethod>
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(<#list bindingData.paramTypes as paramType>${helper.renderGdTypeRefInC(paramType)}<#if paramType_has_next>, </#if></#list>) = method_userdata;
    <#if bindingData.returnType.typeName != "void">
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(<#list bindingData.paramTypes as paramType>${helper.renderValueRef(paramType, "arg${paramType_index}")}<#if paramType_has_next>, </#if></#list>);
        godot_Variant ret = ${helper.renderPackFunctionName(bindingData.returnType)}(${helper.renderValueRef(bindingData.returnType, "r")});
        godot_variant_new_copy(r_return, &ret);
        godot_Variant_destroy(&ret);
        <#assign returnObjectConsumeStmt = helper.renderCallWrapperOwnedObjectReturnConsumeStmt(bindingData.returnType, "r")>
        <#if returnObjectConsumeStmt?has_content>
        ${returnObjectConsumeStmt}
        </#if>
        <#assign returnCleanupStmt = helper.renderCallWrapperDestroyStmt(bindingData.returnType, "r")>
        <#if returnCleanupStmt?has_content>
        ${returnCleanupStmt}
        </#if>
    <#else>
        (function(<#list bindingData.paramTypes as paramType>${helper.renderValueRef(paramType, "arg${paramType_index}")}<#if paramType_has_next>, </#if></#list>));
    </#if>
<#else>
    ${helper.renderRegisteredMethodSelfFatType(bindingData)} self_fat = ${helper.renderRegisteredMethodSelfFatExpr(bindingData)};
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(${helper.renderRegisteredMethodSelfFatType(bindingData)}<#list bindingData.paramTypes as paramType>, ${helper.renderGdTypeRefInC(paramType)}</#list>) = method_userdata;
    <#if bindingData.returnType.typeName != "void">
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(self_fat<#list bindingData.paramTypes as paramType>, ${helper.renderValueRef(paramType, "arg${paramType_index}")}</#list>);
        godot_Variant ret = ${helper.renderPackFunctionName(bindingData.returnType)}(${helper.renderValueRef(bindingData.returnType, "r")});
        godot_variant_new_copy(r_return, &ret);
        godot_Variant_destroy(&ret);
        <#assign returnObjectConsumeStmt = helper.renderCallWrapperOwnedObjectReturnConsumeStmt(bindingData.returnType, "r")>
        <#if returnObjectConsumeStmt?has_content>
        ${returnObjectConsumeStmt}
        </#if>
        <#assign returnCleanupStmt = helper.renderCallWrapperDestroyStmt(bindingData.returnType, "r")>
        <#if returnCleanupStmt?has_content>
        ${returnCleanupStmt}
        </#if>
    <#else>
        (function(self_fat<#list bindingData.paramTypes as paramType>, ${helper.renderValueRef(paramType, "arg${paramType_index}")}</#list>));
    </#if>
</#if>
    <#assign argCount = bindingData.paramTypes?size>
    <#list bindingData.paramTypes?reverse as paramType>
        <#assign argIndex = argCount - paramType_index - 1>
        <#assign argCleanupStmt = helper.renderCallWrapperDestroyStmt(paramType, "arg${argIndex}")>
        <#if argCleanupStmt?has_content>
        ${argCleanupStmt}
        </#if>
    </#list>
}

static void ptrcall${helper.renderFuncBindName(bindingData)}(
    void* method_userdata, GDExtensionClassInstancePtr p_instance,
    const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_return) {
    // Object args/returns use raw Godot pointer slots; self is owner fat for instance methods.
<#list bindingData.paramTypes as paramType>
<#if helper.checkObjectType(paramType)>
    ${helper.renderPtrcallObjectArgDecl(paramType, paramType_index)}
</#if>
</#list>
<#if bindingData.staticMethod>
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(<#list bindingData.paramTypes as paramType>${helper.renderGdTypeRefInC(paramType)}<#if paramType_has_next>, </#if></#list>) = method_userdata;
<#else>
    ${helper.renderRegisteredMethodSelfFatType(bindingData)} self_fat = ${helper.renderRegisteredMethodSelfFatExpr(bindingData)};
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(${helper.renderRegisteredMethodSelfFatType(bindingData)}<#list bindingData.paramTypes as paramType>, ${helper.renderGdTypeRefInC(paramType)}</#list>) = method_userdata;
</#if>
<#if bindingData.returnType.typeName == "void">
    <#if bindingData.staticMethod>
        (function(<#list bindingData.paramTypes as paramType><#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if><#if paramType_has_next>, </#if></#list>));
    <#else>
        (function(self_fat<#list bindingData.paramTypes as paramType>, <#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if></#list>));
    </#if>
<#elseif helper.checkObjectType(bindingData.returnType)>
    <#if bindingData.staticMethod>
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(<#list bindingData.paramTypes as paramType><#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if><#if paramType_has_next>, </#if></#list>);
    <#else>
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(self_fat<#list bindingData.paramTypes as paramType>, <#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if></#list>);
    </#if>
        ${helper.renderPtrcallObjectReturnWrite(bindingData.returnType, "r")}
<#else>
    <#if bindingData.staticMethod>
        *((${helper.renderGdTypeInC(bindingData.returnType)}*)r_return) = function(<#list bindingData.paramTypes as paramType><#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if><#if paramType_has_next>, </#if></#list>);
    <#else>
        *((${helper.renderGdTypeInC(bindingData.returnType)}*)r_return) = function(self_fat<#list bindingData.paramTypes as paramType>, <#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if></#list>);
    </#if>
</#if>
}

static void gdcc_bind_method${helper.renderFuncBindName(bindingData)}(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function<#if bindingData.paramTypes?size gt 0>,</#if>
    <#list bindingData.paramTypes as paramType>
        const godot_StringName* arg${paramType_index}_name,
        const GDExtensionVariantType arg${paramType_index}_type<#if paramType_has_next>,</#if>
    </#list><#if bindingData.defaultVariables?size gt 0>,</#if>
    <#list bindingData.defaultVariables as defaultVarType>
        const ${helper.renderGdTypeRefInC(defaultVarType)} default_${defaultVarType_index}_value<#if defaultVarType_has_next>,</#if>
    </#list>) {

    GDExtensionClassMethodCall call_func = call${helper.renderFuncBindName(bindingData)};
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall${helper.renderFuncBindName(bindingData)};

    // Bound-slot outward metadata stays centralized in CGenHelper so Variant, typed Array and typed
    // Dictionary keep sharing one backend-owned contract instead of growing template-local special cases.
    GDExtensionPropertyInfo args_info[] = {
    <#list bindingData.paramTypes as paramType>
        <#assign boundMetadata = helper.renderBoundMetadata(paramType, "godot_PROPERTY_USAGE_DEFAULT", "method arg")>
        gdcc_make_property_full(arg${paramType_index}_type, arg${paramType_index}_name, ${boundMetadata.hintEnumLiteral}, ${boundMetadata.hintStringExpr}, ${boundMetadata.classNameExpr}, ${boundMetadata.usageExpr}),
    </#list>
    };
    GDExtensionClassMethodArgumentMetadata args_metadata[] = {
    <#list bindingData.paramTypes as paramType>
        GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
    </#list>
    };

    <#if bindingData.defaultVariables?size gt 0>
        // Default argument variants
        <#list bindingData.defaultVariables as defaultVarType>
            godot_Variant default_var_${defaultVarType_index} = ${helper.renderPackFunctionName(defaultVarType)}(default_${defaultVarType_index}_value);
        </#list>
        GDExtensionVariantPtr default_args_ptrs[] = {
        // Default argument pointers
        <#list bindingData.defaultVariables as defaultVarType>
            &default_var_${defaultVarType_index},
        </#list>
        };
    </#if>
    <#if bindingData.returnType.typeName != "void">
        // Return outward metadata reuses the same helper path as arguments so typed Array / typed
        // Dictionary hints stay consistent across both sides of the method boundary.
        <#assign returnMetadata = helper.renderBoundMetadata(bindingData.returnType, "godot_PROPERTY_USAGE_DEFAULT", "method return")>
        GDExtensionPropertyInfo return_info = gdcc_make_property_full(${returnMetadata.typeEnumLiteral}, GD_STATIC_SN(u8""), ${returnMetadata.hintEnumLiteral}, ${returnMetadata.hintStringExpr}, ${returnMetadata.classNameExpr}, ${returnMetadata.usageExpr});
    </#if>
    GDExtensionClassMethodInfo method_info = { 0 };
    method_info.name = method_name;
    method_info.method_userdata = function;
    method_info.call_func = call_func;
    method_info.ptrcall_func = ptrcall_func;
    method_info.method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT<#if bindingData.staticMethod> | GDEXTENSION_METHOD_FLAG_STATIC</#if>;
    <#if bindingData.returnType.typeName != "void">
        method_info.has_return_value = true;
        method_info.return_value_info = &return_info;
        method_info.return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE;
    <#else>
        method_info.has_return_value = false;
    </#if>
    method_info.argument_count = ${bindingData.paramTypes?size};
    method_info.arguments_info = args_info;
    method_info.arguments_metadata = args_metadata;
    <#if bindingData.defaultVariables?size gt 0>
        method_info.default_argument_count = ${bindingData.defaultVariables?size};
        method_info.default_arguments = default_args_ptrs;
    </#if>
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);
    // Clean up
    <#list bindingData.paramTypes as paramType>
        gdcc_destruct_property(&args_info[${paramType_index}]);
    </#list>
    <#list bindingData.defaultVariables as defaultVarType>
        godot_Variant_destroy(&default_var_${defaultVarType_index});
    </#list>
    <#if bindingData.returnType.typeName != "void">
        gdcc_destruct_property(&return_info);
    </#if>
}
</#list>

#endif //GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H
