#ifndef GDCC_BIND_METHOD_H
#define GDCC_BIND_METHOD_H

#include <gdextension-lite.h>

static GDExtensionPropertyInfo gdcc_make_property_full(
    const GDExtensionVariantType type,
    const godot_StringName* name,
    const uint32_t hint,
    const godot_String* hint_string,
    const godot_StringName* class_name,
    const uint32_t usage_flags) {
    godot_StringName* prop_name = godot_mem_alloc(sizeof(godot_StringName));
    *prop_name = godot_new_StringName_with_StringName(name);
    godot_String* prop_hint_string = godot_mem_alloc(sizeof(godot_String));
    *prop_hint_string = godot_new_String_with_String(hint_string);
    godot_StringName* prop_class_name = godot_mem_alloc(sizeof(godot_StringName));
    *prop_class_name = godot_new_StringName_with_StringName(class_name);

    return (GDExtensionPropertyInfo){
        .name = prop_name,
        .type = type,
        .hint = hint,
        .hint_string = prop_hint_string,
        .class_name = prop_class_name,
        .usage = usage_flags,
    };
}

static GDExtensionPropertyInfo gdcc_make_property(
    const GDExtensionVariantType type,
    const godot_StringName* name) {
    return gdcc_make_property_full(type, name, godot_PROPERTY_HINT_NONE,
        GD_STATIC_S(u8""), GD_STATIC_SN(u8""), godot_PROPERTY_USAGE_DEFAULT);
}

static void gdcc_destruct_property(const GDExtensionPropertyInfo* info) {
    godot_StringName_destroy(info->name);
    godot_String_destroy(info->hint_string);
    godot_StringName_destroy(info->class_name);
    godot_mem_free(info->name);
    godot_mem_free(info->hint_string);
    godot_mem_free(info->class_name);
}

static void gdcc_bind_property(
    const godot_StringName* class_name,
    const godot_StringName* name,
    const GDExtensionVariantType type,
    const godot_StringName* getter,
    const godot_StringName* setter) {
    godot_StringName class_string_name = godot_new_StringName_with_StringName(class_name);
    const GDExtensionPropertyInfo info = gdcc_make_property_full(type, name, godot_PROPERTY_HINT_NONE,
        GD_STATIC_S(u8""), class_name, godot_PROPERTY_USAGE_DEFAULT);
    godot_StringName getter_name = godot_new_StringName_with_StringName(getter);
    godot_StringName setter_name = godot_new_StringName_with_StringName(setter);

    godot_classdb_register_extension_class_property(class_library, &class_string_name, &info, &setter_name,
                                                    &getter_name);

    godot_StringName_destroy(&class_string_name);
    gdcc_destruct_property(&info);
    godot_StringName_destroy(&getter_name);
    godot_StringName_destroy(&setter_name);
}

static void call_1_float_arg_ret_float(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                    const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
                                    GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
    // Check argument count.
    if (p_argument_count < 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
        r_error->expected = 1;
        return;
    }
    if (p_argument_count > 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = 1;
        return;
    }

    // Check the argument type.
    const GDExtensionVariantType type = godot_variant_get_type(p_args[0]);
    if (type != GDEXTENSION_VARIANT_TYPE_FLOAT) {
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
        r_error->expected = GDEXTENSION_VARIANT_TYPE_FLOAT;
        r_error->argument = 0;
        return;
    }

    // Extract the argument.
    const double arg0 = godot_new_float_with_Variant((GDExtensionVariantPtr)p_args[0]);

    // Call the function.
    godot_float (*function)(void*, godot_float) = method_userdata;
    godot_Variant ret = godot_new_Variant_with_float(function(p_instance, arg0));
    godot_variant_new_copy(r_return, &ret);
}

static void ptrcall_1_float_arg_ret_float(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                       const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_return) {
    // Call the function.
    godot_float (*function)(void*, double) = method_userdata;
    *((godot_float*)r_return) = function(p_instance, *((double*)p_args[0]));
}

static void gdcc_bind_method_1_float_r_float(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function,
    const godot_StringName* arg1_name,
    const GDExtensionVariantType arg1_type) {

    GDExtensionClassMethodCall call_func = call_1_float_arg_ret_float;
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall_1_float_arg_ret_float;

    GDExtensionPropertyInfo args_info[] = {
        gdcc_make_property(arg1_type, arg1_name),
    };
    GDExtensionClassMethodArgumentMetadata args_metadata[] = {
        GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
    };
    GDExtensionPropertyInfo return_info = gdcc_make_property(GDEXTENSION_VARIANT_TYPE_FLOAT, GD_STATIC_SN(u8""));
    GDExtensionClassMethodInfo method_info = {
        .name = method_name,
        .method_userdata = function,
        .call_func = call_func,
        .ptrcall_func = ptrcall_func,
        .method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT,
        .has_return_value = true,
        .return_value_info = &return_info,
        .return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_DOUBLE,
        .argument_count = 1,
        .arguments_info = args_info,
        .arguments_metadata = args_metadata,
    };
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);

    gdcc_destruct_property(&args_info[0]);
    gdcc_destruct_property(&return_info);
}

static void call_1_bool_arg_ret_float(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                    const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
                                    GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
    // Check argument count.
    if (p_argument_count < 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
        r_error->expected = 1;
        return;
    }
    if (p_argument_count > 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = 1;
        return;
    }

    // Check the argument type.
    const GDExtensionVariantType type = godot_variant_get_type(p_args[0]);
    if (type != GDEXTENSION_VARIANT_TYPE_BOOL) {
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
        r_error->expected = GDEXTENSION_VARIANT_TYPE_BOOL;
        r_error->argument = 0;
        return;
    }

    // Extract the argument.
    const godot_bool arg0 = godot_new_bool_with_Variant((GDExtensionVariantPtr)p_args[0]);

    // Call the function.
    godot_float (*function)(void*, godot_bool) = method_userdata;
    godot_Variant ret = godot_new_Variant_with_float(function(p_instance, arg0));
    godot_variant_new_copy(r_return, &ret);
}

static void ptrcall_1_bool_arg_ret_float(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                       const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_return) {
    // Call the function.
    godot_float (*function)(void*, godot_bool) = method_userdata;
    *((godot_float*)r_return) = function(p_instance, *((godot_bool*)p_args[0]));
}

static void gdcc_bind_method_1_bool_r_float(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function,
    const godot_StringName* arg1_name,
    const GDExtensionVariantType arg1_type) {

    GDExtensionClassMethodCall call_func = call_1_bool_arg_ret_float;
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall_1_bool_arg_ret_float;

    GDExtensionPropertyInfo args_info[] = {
        gdcc_make_property(arg1_type, arg1_name),
    };
    GDExtensionClassMethodArgumentMetadata args_metadata[] = {
        GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
    };
    GDExtensionPropertyInfo return_info = gdcc_make_property(GDEXTENSION_VARIANT_TYPE_FLOAT, GD_STATIC_SN(u8""));
    GDExtensionClassMethodInfo method_info = {
        .name = method_name,
        .method_userdata = function,
        .call_func = call_func,
        .ptrcall_func = ptrcall_func,
        .method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT,
        .has_return_value = true,
        .return_value_info = &return_info,
        .return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_DOUBLE,
        .argument_count = 1,
        .arguments_info = args_info,
        .arguments_metadata = args_metadata,
    };
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);

    gdcc_destruct_property(&args_info[0]);
    gdcc_destruct_property(&return_info);
}

static void call_0_arg_ret_float(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                 const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
                                 GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
    if (p_argument_count != 0) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = 0;
        return;
    }

    // Call the function.
    double (*function)(void*) = method_userdata;
    double result = function(p_instance);
    // Set resulting Variant.
    const godot_Variant result_variant = godot_new_Variant_with_float(result);
    godot_variant_new_copy(r_return, &result_variant);
}

static void ptrcall_0_arg_ret_float(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                    const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_ret) {
    double (*function)(void*) = method_userdata;
    *((double*)r_ret) = function(p_instance);
}

static void gdcc_bind_method_0_r_float(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function) {

    GDExtensionClassMethodCall call_func = call_0_arg_ret_float;
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall_0_arg_ret_float;

    GDExtensionPropertyInfo return_info = gdcc_make_property(GDEXTENSION_VARIANT_TYPE_FLOAT, GD_STATIC_SN(u8""));
    GDExtensionClassMethodInfo method_info = {
        .name = method_name,
        .method_userdata = function,
        .call_func = call_func,
        .ptrcall_func = ptrcall_func,
        .method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT,
        .has_return_value = true,
        .return_value_info = &return_info,
        .return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
        .argument_count = 0,
    };
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);

    gdcc_destruct_property(&return_info);
}

static void call_1_float_arg_no_ret(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                    const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
                                    GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
    // Check argument count.
    if (p_argument_count < 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
        r_error->expected = 1;
        return;
    }
    if (p_argument_count > 1) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = 1;
        return;
    }

    // Check the argument type.
    const GDExtensionVariantType type = godot_variant_get_type(p_args[0]);
    if (type != GDEXTENSION_VARIANT_TYPE_FLOAT) {
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
        r_error->expected = GDEXTENSION_VARIANT_TYPE_FLOAT;
        r_error->argument = 0;
        return;
    }

    // Extract the argument.
    const double arg0 = godot_new_float_with_Variant((GDExtensionVariantPtr)p_args[0]);

    // Call the function.
    void (*function)(void*, double) = method_userdata;
    function(p_instance, arg0);
}

static void ptrcall_1_float_arg_no_ret(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                       const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_ret) {
    // Call the function.
    void (*function)(void*, double) = method_userdata;
    function(p_instance, *((double*)p_args[0]));
}

static void gdcc_bind_method_1(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function,
    const godot_StringName* arg1_name,
    GDExtensionVariantType arg1_type) {

    GDExtensionClassMethodCall call_func = call_1_float_arg_no_ret;
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall_1_float_arg_no_ret;

    GDExtensionPropertyInfo args_info[] = {
        gdcc_make_property(arg1_type, arg1_name),
    };
    GDExtensionClassMethodArgumentMetadata args_metadata[] = {
        GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
    };
    GDExtensionClassMethodInfo method_info = {
        .name = method_name,
        .method_userdata = function,
        .call_func = call_func,
        .ptrcall_func = ptrcall_func,
        .method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT,
        .has_return_value = false,
        .argument_count = 1,
        .arguments_info = args_info,
        .arguments_metadata = args_metadata,
    };
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);

    gdcc_destruct_property(&args_info[0]);
}

static void call_0_arg_no_ret(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                              const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
                              GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
    if (p_argument_count != 0) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = 0;
        return;
    }

    // Call the function.
    void (*function)(void*) = method_userdata;
    function(p_instance);
}

static void ptrcall_0_arg_no_ret(void* method_userdata, GDExtensionClassInstancePtr p_instance,
                                 const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_ret) {
    // Call the function.
    void (*function)(void*) = method_userdata;
    function(p_instance);
}

static void gdcc_bind_method_0(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function) {

    GDExtensionClassMethodCall call_func = call_0_arg_no_ret;
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall_0_arg_no_ret;

    GDExtensionClassMethodInfo method_info = {
        .name = method_name,
        .method_userdata = function,
        .call_func = call_func,
        .ptrcall_func = ptrcall_func,
        .method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT,
        .has_return_value = false,
        .argument_count = 0,
    };
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);
}

#endif //GDCC_BIND_METHOD_H
