#include <godot_binding.h>

static GDExtensionClassLibraryPtr class_library = NULL;

#include <gdcc_callable.h>

// Test-only seam from gdcc_hrx.c (compiled with -DGDCC_HRX_TEST_HOOKS):
// simulate a NEW library image between generations.
void gdcc_hrx_test_reset_image_state(void);

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FAKE_MAX_BINDINGS 16

static int g_mem_balance = 0;
static int g_print_error_count = 0;
static char g_last_error[256] = {0};
static int g_engine_marker = 0;

static void fail(const char *msg) {
    printf("FAIL %s\n", msg);
    fflush(stdout);
    exit(1);
}
#define CHECK(cond, msg) do { if (!(cond)) fail(msg); } while (0)

static void log_event(const char *event) {
    printf("EV %s\n", event);
    fflush(stdout);
}

// ---------- fake GDExtension interface entry points ----------
static void *fake_mem_alloc(size_t bytes) {
    g_mem_balance++;
    return malloc(bytes == 0 ? 1 : bytes);
}
static void *fake_mem_realloc(void *ptr, size_t bytes) {
    if (ptr == NULL) g_mem_balance++;
    return realloc(ptr, bytes == 0 ? 1 : bytes);
}
static void fake_mem_free(void *ptr) {
    if (ptr != NULL) {
        g_mem_balance--;
        free(ptr);
    }
}
static void fake_print_error(const char *desc, const char *func, const char *file, int32_t line, GDExtensionBool notify) {
    (void)func; (void)file; (void)line; (void)notify;
    g_print_error_count++;
    snprintf(g_last_error, sizeof(g_last_error), "%s", desc != NULL ? desc : "");
}

// ---------- fake Callable custom object layer ----------
// godot_Callable layout: bytes [0..8) FakeCallableCustom pointer (NULL = invalid).
typedef struct FakeCallableCustom {
    void *userdata;
    GDExtensionCallableCustomCall call_func;
    GDExtensionCallableCustomIsValid is_valid_func;
    GDExtensionCallableCustomFree free_func;
    GDExtensionCallableCustomGetArgumentCount argc_func;
    int refs;
} FakeCallableCustom;

static FakeCallableCustom *fake_callable_custom_of(const godot_Callable *callable) {
    FakeCallableCustom *custom;
    memcpy(&custom, callable, sizeof(custom));
    return custom;
}
static void fake_callable_custom_write(godot_Callable *callable, FakeCallableCustom *custom) {
    memset(callable, 0, sizeof(*callable));
    memcpy(callable, &custom, sizeof(custom));
}
static void fake_callable_custom_release(FakeCallableCustom *custom) {
    if (custom == NULL) return;
    custom->refs--;
    if (custom->refs == 0) {
        if (custom->free_func != NULL) custom->free_func(custom->userdata);
        fake_mem_free(custom);
    }
}
// Godot's default custom-Callable equality: (call_func, userdata).
static int fake_callable_equal(const FakeCallableCustom *a, const FakeCallableCustom *b) {
    if (a == b) return 1;
    if (a == NULL || b == NULL) return 0;
    return a->call_func == b->call_func && a->userdata == b->userdata;
}
static void fake_callable_custom_create2(GDExtensionUninitializedTypePtr r_callable, GDExtensionCallableCustomInfo2 *info) {
    FakeCallableCustom *custom = fake_mem_alloc(sizeof(FakeCallableCustom));
    if (custom == NULL) fail("callable custom allocation failed");
    custom->userdata = info->callable_userdata;
    custom->call_func = info->call_func;
    custom->is_valid_func = info->is_valid_func;
    custom->free_func = info->free_func;
    custom->argc_func = info->get_argument_count_func;
    custom->refs = 1;
    fake_callable_custom_write((godot_Callable *)r_callable, custom);
}
static void fake_callable_ptr_destructor(GDExtensionTypePtr ptr) {
    fake_callable_custom_release(fake_callable_custom_of((const godot_Callable *)ptr));
}
static void fake_ptr_destructor_noop(GDExtensionTypePtr ptr) {
    (void)ptr;
}
static GDExtensionPtrDestructor fake_variant_get_ptr_destructor(GDExtensionVariantType type) {
    if (type == GDEXTENSION_VARIANT_TYPE_CALLABLE) return fake_callable_ptr_destructor;
    return fake_ptr_destructor_noop;
}

// ---------- fake instance-binding table (engine get_instance_binding semantics) ----------
static struct { GDExtensionObjectPtr obj; void *token; void *binding; } g_bindings[FAKE_MAX_BINDINGS];
static int g_binding_count = 0;
static int g_create_callback_calls = 0;
static int g_free_binding_calls = 0;
static int g_anchor_free_cb_was_null = -1;
static int g_anchor_reference_cb_was_null = -1;

static void fake_append_binding(GDExtensionObjectPtr obj, void *token, void *binding) {
    if (g_binding_count >= FAKE_MAX_BINDINGS) fail("binding table overflow");
    g_bindings[g_binding_count].obj = obj;
    g_bindings[g_binding_count].token = token;
    g_bindings[g_binding_count].binding = binding;
    g_binding_count++;
}
static void *fake_get_binding(GDExtensionObjectPtr obj, void *token, const GDExtensionInstanceBindingCallbacks *callbacks) {
    // Real engine semantics (object.cpp:2116-2147): the first token match wins;
    // when its binding is NULL (miss OR tombstone) and callbacks are given, a NEW
    // slot is appended and kept even if create_callback returns NULL.
    void *binding = NULL;
    for (int i = 0; i < g_binding_count; i++) {
        if (g_bindings[i].obj == obj && g_bindings[i].token == token) {
            binding = g_bindings[i].binding;
            break;
        }
    }
    if (binding != NULL || callbacks == NULL) return binding;
    // The engine copies free/reference into the slot; HRX requires both to be NULL.
    g_anchor_free_cb_was_null = callbacks->free_callback == NULL ? 1 : 0;
    g_anchor_reference_cb_was_null = callbacks->reference_callback == NULL ? 1 : 0;
    if (callbacks->create_callback == NULL) return NULL;
    g_create_callback_calls++;
    binding = callbacks->create_callback(token, obj);
    fake_append_binding(obj, token, binding);
    return binding;
}
static void fake_free_binding(GDExtensionObjectPtr obj, void *token) {
    // Real engine semantics (object.cpp:2165-2185): remove the FIRST token match
    // and shift the remaining slots down.
    for (int i = 0; i < g_binding_count; i++) {
        if (g_bindings[i].obj == obj && g_bindings[i].token == token) {
            for (int j = i; j + 1 < g_binding_count; j++) {
                g_bindings[j] = g_bindings[j + 1];
            }
            g_binding_count--;
            g_free_binding_calls++;
            return;
        }
    }
}

static void fake_unused_interface(void) {
}
static GDExtensionInterfaceFunctionPtr fake_get_proc_address(const char *name) {
    if (strcmp(name, "mem_alloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_alloc;
    if (strcmp(name, "mem_realloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_realloc;
    if (strcmp(name, "mem_free") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_free;
    if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)fake_print_error;
    if (strcmp(name, "callable_custom_create2") == 0) return (GDExtensionInterfaceFunctionPtr)fake_callable_custom_create2;
    if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_destructor;
    if (strcmp(name, "object_get_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_get_binding;
    if (strcmp(name, "object_free_instance_binding") == 0) return (GDExtensionInterfaceFunctionPtr)fake_free_binding;
    return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
}
