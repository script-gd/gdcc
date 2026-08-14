#ifndef GDCC_CALLABLE_H
#define GDCC_CALLABLE_H

#include <godot_binding.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

/// Heap-interned identity for a no-receiver custom Callable (`construct_standalone_callable`).
/// Each unique `(kind, owner, name)` owns one `godot_mem_alloc`'d spec. `free_func` is a no-op
/// because many Callable values share that pointer; the table is freed on library unload.
///
/// The includer must declare `class_library` before this header is processed.
typedef struct gdcc_standalone_callable_spec {
    const char *kind;
    const char *owner;
    const char *name;
    godot_int utility_hash;
    int argument_count;
    godot_bool is_vararg;
    godot_bool returns_value;
} gdcc_standalone_callable_spec;

typedef struct gdcc_standalone_callable_registry {
    gdcc_standalone_callable_spec **items;
    uint32_t count;
    uint32_t capacity;
} gdcc_standalone_callable_registry;

static gdcc_standalone_callable_registry g_standalone_callable_registry = {nullptr};

static godot_bool gdcc_standalone_callable_registry_add(gdcc_standalone_callable_spec *spec) {
    if (g_standalone_callable_registry.count == g_standalone_callable_registry.capacity) {
        const uint32_t new_cap = (g_standalone_callable_registry.capacity == 0)
                ? 16u
                : (g_standalone_callable_registry.capacity * 2u);
        const size_t new_size = (size_t)new_cap * sizeof(gdcc_standalone_callable_spec *);
        gdcc_standalone_callable_spec **grown = (g_standalone_callable_registry.items == NULL)
                ? (gdcc_standalone_callable_spec **)godot_mem_alloc(new_size)
                : (gdcc_standalone_callable_spec **)godot_mem_realloc(
                        g_standalone_callable_registry.items,
                        new_size
                );
        if (grown == NULL) {
            return false;
        }
        g_standalone_callable_registry.items = grown;
        g_standalone_callable_registry.capacity = new_cap;
    }
    g_standalone_callable_registry.items[g_standalone_callable_registry.count++] = spec;
    return true;
}

static void gdcc_standalone_callable_registry_destroy_all(void) {
    for (uint32_t i = 0; i < g_standalone_callable_registry.count; ++i) {
        godot_mem_free(g_standalone_callable_registry.items[i]);
    }
    if (g_standalone_callable_registry.items != NULL) {
        godot_mem_free(g_standalone_callable_registry.items);
    }
    g_standalone_callable_registry.items = NULL;
    g_standalone_callable_registry.count = 0;
    g_standalone_callable_registry.capacity = 0;
}

static uint32_t gdcc_fnv1a32_update(uint32_t hash, const char *text) {
    const unsigned char *bytes = (const unsigned char *)(text ? text : "");
    while (*bytes != 0) {
        hash ^= (uint32_t)(*bytes++);
        hash *= 16777619u;
    }
    return hash;
}

static uint32_t gdcc_standalone_callable_hash(void *userdata) {
    const gdcc_standalone_callable_spec *spec = (const gdcc_standalone_callable_spec *)userdata;
    uint32_t hash = 2166136261u;
    if (spec == NULL) {
        return hash;
    }
    hash = gdcc_fnv1a32_update(hash, spec->kind);
    hash = gdcc_fnv1a32_update(hash, "|");
    hash = gdcc_fnv1a32_update(hash, spec->owner);
    hash = gdcc_fnv1a32_update(hash, "|");
    hash = gdcc_fnv1a32_update(hash, spec->name);
    return hash;
}

static GDExtensionBool gdcc_standalone_callable_equal(void *userdata_a, void *userdata_b) {
    const gdcc_standalone_callable_spec *left = (const gdcc_standalone_callable_spec *)userdata_a;
    const gdcc_standalone_callable_spec *right = (const gdcc_standalone_callable_spec *)userdata_b;
    if (left == right) {
        return true;
    }
    if (left == NULL || right == NULL) {
        return false;
    }
    return strcmp(left->kind ? left->kind : "", right->kind ? right->kind : "") == 0
            && strcmp(left->owner ? left->owner : "", right->owner ? right->owner : "") == 0
            && strcmp(left->name ? left->name : "", right->name ? right->name : "") == 0;
}

static GDExtensionBool gdcc_standalone_callable_less(void *userdata_a, void *userdata_b) {
    const gdcc_standalone_callable_spec *left = (const gdcc_standalone_callable_spec *)userdata_a;
    const gdcc_standalone_callable_spec *right = (const gdcc_standalone_callable_spec *)userdata_b;
    if (left == NULL || right == NULL) {
        return left != NULL && right == NULL;
    }
    int kind_cmp = strcmp(left->kind ? left->kind : "", right->kind ? right->kind : "");
    if (kind_cmp != 0) {
        return kind_cmp < 0;
    }
    int owner_cmp = strcmp(left->owner ? left->owner : "", right->owner ? right->owner : "");
    if (owner_cmp != 0) {
        return owner_cmp < 0;
    }
    return strcmp(left->name ? left->name : "", right->name ? right->name : "") < 0;
}

static GDExtensionBool gdcc_standalone_callable_is_valid(void *userdata) {
    return userdata != NULL;
}

static void gdcc_standalone_callable_free(void *userdata) {
    (void)userdata;
}

static void gdcc_standalone_callable_to_string(
        void *userdata,
        GDExtensionBool *r_is_valid,
        GDExtensionStringPtr r_out
) {
    const gdcc_standalone_callable_spec *spec = (const gdcc_standalone_callable_spec *)userdata;
    char buf[256];
    if (spec == NULL) {
        if (r_is_valid != NULL) {
            *r_is_valid = false;
        }
        godot_string_new_with_utf8_chars(r_out, "GDCC.standalone(<invalid>)");
        return;
    }
    if (spec->owner != NULL && spec->owner[0] != '\0') {
        snprintf(buf, sizeof(buf), "GDCC.%s(%s.%s)", spec->kind, spec->owner, spec->name);
    } else {
        snprintf(buf, sizeof(buf), "GDCC.%s(%s)", spec->kind, spec->name);
    }
    if (r_is_valid != NULL) {
        *r_is_valid = true;
    }
    godot_string_new_with_utf8_chars(r_out, buf);
}

static GDExtensionInt gdcc_standalone_callable_get_argument_count(void *userdata, GDExtensionBool *r_is_valid) {
    const gdcc_standalone_callable_spec *spec = (const gdcc_standalone_callable_spec *)userdata;
    if (r_is_valid != NULL) {
        *r_is_valid = spec != NULL;
    }
    return spec != NULL ? spec->argument_count : 0;
}

static GDExtensionPtrUtilityFunction gdcc_standalone_resolve_utility(const gdcc_standalone_callable_spec *spec) {
    if (spec == NULL || spec->name == NULL || spec->utility_hash == 0) {
        return NULL;
    }
    godot_StringName utility_name = godot_new_StringName_with_utf8_chars(spec->name);
    GDExtensionPtrUtilityFunction resolved = godot_variant_get_ptr_utility_function(
            &utility_name,
            spec->utility_hash
    );
    godot_StringName_destroy(&utility_name);
    return resolved;
}

static GDExtensionMethodBindPtr gdcc_classdb_class_call_static_bind = NULL;
#define GDCC_STANDALONE_STATIC_STACK_ARGC 16

static godot_Variant gdcc_classdb_class_call_static(
        const char *owner,
        const char *method,
        const GDExtensionConstVariantPtr *args,
        GDExtensionInt argc,
        GDExtensionCallError *r_error
) {
    if (gdcc_classdb_class_call_static_bind == NULL) {
        godot_StringName class_name = godot_new_StringName_with_utf8_chars("ClassDB");
        godot_StringName method_name = godot_new_StringName_with_utf8_chars("class_call_static");
        gdcc_classdb_class_call_static_bind = godot_classdb_get_method_bind(
                &class_name,
                &method_name,
                3344196419LL
        );
        godot_StringName_destroy(&class_name);
        godot_StringName_destroy(&method_name);
    }
    if (gdcc_classdb_class_call_static_bind == NULL || owner == NULL || method == NULL) {
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        }
        return godot_new_Variant_nil();
    }

    godot_StringName owner_name = godot_new_StringName_with_utf8_chars(owner);
    godot_StringName method_name = godot_new_StringName_with_utf8_chars(method);
    godot_Variant owner_variant = godot_new_Variant_with_StringName(&owner_name);
    godot_Variant method_variant = godot_new_Variant_with_StringName(&method_name);
    GDExtensionInt total_argc = 2 + (argc > 0 ? argc : 0);
    GDExtensionConstVariantPtr stack_args[2 + GDCC_STANDALONE_STATIC_STACK_ARGC];
    GDExtensionConstVariantPtr *call_args = stack_args;
    if (argc > GDCC_STANDALONE_STATIC_STACK_ARGC) {
        call_args = (GDExtensionConstVariantPtr *)godot_mem_alloc(
                (size_t)total_argc * sizeof(GDExtensionConstVariantPtr)
        );
        if (call_args == NULL) {
            godot_Variant_destroy(&owner_variant);
            godot_Variant_destroy(&method_variant);
            godot_StringName_destroy(&owner_name);
            godot_StringName_destroy(&method_name);
            if (r_error != NULL) {
                r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
            }
            return godot_new_Variant_nil();
        }
    }
    call_args[0] = (GDExtensionConstVariantPtr)&owner_variant;
    call_args[1] = (GDExtensionConstVariantPtr)&method_variant;
    for (GDExtensionInt index = 0; index < argc; index++) {
        call_args[2 + index] = args[index];
    }

    godot_Variant result;
    GDExtensionCallError error = { 0 };
    godot_object_method_bind_call(
            gdcc_classdb_class_call_static_bind,
            (GDExtensionObjectPtr)godot_ClassDB_singleton(),
            call_args,
            total_argc,
            (GDExtensionUninitializedVariantPtr)&result,
            &error
    );
    if (call_args != stack_args) {
        godot_mem_free(call_args);
    }
    godot_Variant_destroy(&owner_variant);
    godot_Variant_destroy(&method_variant);
    godot_StringName_destroy(&owner_name);
    godot_StringName_destroy(&method_name);
    if (r_error != NULL) {
        *r_error = error;
    }
    if (error.error != GDEXTENSION_CALL_OK) {
        return godot_new_Variant_nil();
    }
    return result;
}

static void gdcc_standalone_callable_call(
        void *userdata,
        const GDExtensionConstVariantPtr *p_args,
        GDExtensionInt p_argument_count,
        GDExtensionVariantPtr r_return,
        GDExtensionCallError *r_error
) {
    const gdcc_standalone_callable_spec *spec = (const gdcc_standalone_callable_spec *)userdata;
    if (r_error != NULL) {
        r_error->error = GDEXTENSION_CALL_OK;
        r_error->argument = 0;
        r_error->expected = 0;
    }
    if (spec == NULL || spec->kind == NULL || spec->name == NULL) {
        godot_variant_new_nil(r_return);
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        }
        return;
    }
    if (strcmp(spec->kind, "utility") == 0) {
        GDExtensionPtrUtilityFunction utility = gdcc_standalone_resolve_utility(spec);
        if (utility == NULL) {
            godot_variant_new_nil(r_return);
            if (r_error != NULL) {
                r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
            }
            return;
        }
        // Ptr utilities have no error out-parameter. Fixed-arity ones read argv[0..n)
        // unconditionally, so arity must be checked here. Vararg utilities iterate argc.
        if (!spec->is_vararg && p_argument_count != spec->argument_count) {
            godot_variant_new_nil(r_return);
            if (r_error != NULL) {
                r_error->error = (p_argument_count < spec->argument_count)
                        ? GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS
                        : GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
                r_error->expected = spec->argument_count;
            }
            return;
        }
        godot_variant_new_nil(r_return);
        utility(
                spec->returns_value ? r_return : NULL,
                (const GDExtensionConstTypePtr *)p_args,
                (int)p_argument_count
        );
        return;
    }
    if (strcmp(spec->kind, "static_gdcc") == 0 || strcmp(spec->kind, "static_engine") == 0) {
        GDExtensionCallError static_error = { 0 };
        godot_Variant result = gdcc_classdb_class_call_static(
                spec->owner,
                spec->name,
                p_args,
                p_argument_count,
                &static_error
        );
        *(godot_Variant *)r_return = result;
        if (r_error != NULL) {
            *r_error = static_error;
        }
        return;
    }
    godot_variant_new_nil(r_return);
    if (r_error != NULL) {
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
    }
}

static inline const gdcc_standalone_callable_spec *gdcc_standalone_callable_spec_of(
        const char *kind,
        const char *owner,
        const char *name,
        godot_int utility_hash,
        int argument_count,
        godot_bool is_vararg,
        godot_bool returns_value
) {
    for (uint32_t index = 0; index < g_standalone_callable_registry.count; index++) {
        const gdcc_standalone_callable_spec *existing = g_standalone_callable_registry.items[index];
        if (strcmp(existing->kind, kind) == 0
                && strcmp(existing->owner, owner) == 0
                && strcmp(existing->name, name) == 0) {
            return existing;
        }
    }
    gdcc_standalone_callable_spec *spec =
            (gdcc_standalone_callable_spec *)godot_mem_alloc(sizeof(gdcc_standalone_callable_spec));
    if (spec == NULL) {
        return NULL;
    }
    *spec = (gdcc_standalone_callable_spec){
            .kind = kind,
            .owner = owner,
            .name = name,
            .utility_hash = utility_hash,
            .argument_count = argument_count,
            .is_vararg = is_vararg,
            .returns_value = returns_value,
    };
    if (!gdcc_standalone_callable_registry_add(spec)) {
        godot_mem_free(spec);
        return NULL;
    }
    return spec;
}

static inline godot_Callable gdcc_new_standalone_callable(
        const char *kind,
        const char *owner,
        const char *name,
        godot_int utility_hash,
        int argument_count,
        godot_bool is_vararg,
        godot_bool returns_value
) {
    const gdcc_standalone_callable_spec *spec = gdcc_standalone_callable_spec_of(
            kind,
            owner ? owner : "",
            name,
            utility_hash,
            argument_count,
            is_vararg,
            returns_value
    );
    godot_Callable result;
    if (spec == NULL) {
        memset(&result, 0, sizeof(result));
        return result;
    }
    GDExtensionCallableCustomInfo2 info = {
            .callable_userdata = (void *)spec,
            .token = class_library,
            .object_id = 0,
            .call_func = gdcc_standalone_callable_call,
            .is_valid_func = gdcc_standalone_callable_is_valid,
            .free_func = gdcc_standalone_callable_free,
            .hash_func = gdcc_standalone_callable_hash,
            .equal_func = gdcc_standalone_callable_equal,
            .less_than_func = gdcc_standalone_callable_less,
            .to_string_func = gdcc_standalone_callable_to_string,
            .get_argument_count_func = gdcc_standalone_callable_get_argument_count,
    };
    godot_callable_custom_create2((GDExtensionUninitializedTypePtr)&result, &info);
    return result;
}

#endif //GDCC_CALLABLE_H
