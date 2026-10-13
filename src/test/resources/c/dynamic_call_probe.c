// The fake Variant stores its type tag at byte offset 0 and its payload at offset 8 inside
// the opaque 24-byte godot_Variant storage; the payload is an inline int64/double for
// scalar types and a heap copy for StringName/Vector3 so every value printed below is a
// deterministic constant (no addresses ever reach the event log).

#include <godot_binding.h>
#include <gdcc_call.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_alloc_calls = 0;
// Allocations of `argc * sizeof(godot_Variant)` bytes fingerprint the variadic
// fallback branch's argument array; the statement-expression branch never makes one.
static int g_large_allocs = 0;
static int g_mem_balance = 0;
static int g_error_count = 0;
static int g_variant_creates = 0;
static int g_variant_destroys = 0;

static void fail(const char *msg) {
    printf("FAIL %s\n", msg);
    fflush(stdout);
    exit(1);
}
#define CHECK(cond, msg) do { if (!(cond)) fail(msg); } while (0)

static int32_t tag_of(const godot_Variant *v) { int32_t t; memcpy(&t, v, sizeof(t)); return t; }
static void *payload_of(godot_Variant *v) { return (uint8_t *)v + 8; }
static const void *payload_of_const(const godot_Variant *v) { return (const uint8_t *)v + 8; }

// ---------- interface allocator (also the fallback-branch tripwire) ----------
static void *fake_mem_alloc(size_t bytes) {
    g_alloc_calls++;
    g_mem_balance++;
    if (bytes >= sizeof(godot_Variant)) g_large_allocs++;
    return malloc(bytes == 0 ? 1 : bytes);
}
static void *fake_mem_realloc(void *p, size_t bytes) { if (p == NULL) { g_alloc_calls++; g_mem_balance++; } return realloc(p, bytes == 0 ? 1 : bytes); }
static void fake_mem_free(void *p) { if (p != NULL) { g_mem_balance--; free(p); } }
static void fake_print_error(const char *desc, const char *func, const char *file, int32_t line, GDExtensionBool notify) {
    (void)func; (void)file; (void)line; (void)notify;
    g_error_count++;
    printf("EV engine_error=%s\n", desc != NULL ? desc : "");
}

// ---------- fake StringName: a heap C string in the opaque storage ----------
static void fake_string_name_new_with_utf8_chars(GDExtensionUninitializedStringNamePtr r, const char *text) {
    char *copy = fake_mem_alloc(strlen(text) + 1);
    strcpy(copy, text);
    memset(r, 0, sizeof(godot_StringName));
    memcpy(r, &copy, sizeof(copy));
}
static void fake_string_name_destroy(GDExtensionTypePtr p) {
    char *stored = NULL;
    memcpy(&stored, p, sizeof(stored));
    fake_mem_free(stored);
    memset(p, 0, sizeof(godot_StringName));
}

// ---------- fake Variant constructors/destructor ----------
static void fake_variant_new_nil(GDExtensionUninitializedVariantPtr r) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_NIL;
    memcpy(r, &tag, sizeof(tag));
    g_variant_creates++;
}
static void fake_variant_destroy(GDExtensionVariantPtr v) {
    int32_t tag = tag_of((godot_Variant *)v);
    if (tag == GDEXTENSION_VARIANT_TYPE_STRING_NAME || tag == GDEXTENSION_VARIANT_TYPE_VECTOR3) {
        void *heap = NULL;
        memcpy(&heap, payload_of_const((godot_Variant *)v), sizeof(heap));
        fake_mem_free(heap);
    }
    memset(v, 0, sizeof(godot_Variant));
    g_variant_destroys++;
}
static void ctor_int(GDExtensionUninitializedVariantPtr r, GDExtensionTypePtr value) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_INT;
    memcpy(r, &tag, sizeof(tag));
    int64_t payload = *(const godot_int *)value;
    memcpy(payload_of((godot_Variant *)r), &payload, sizeof(payload));
    g_variant_creates++;
}
static void ctor_float(GDExtensionUninitializedVariantPtr r, GDExtensionTypePtr value) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_FLOAT;
    memcpy(r, &tag, sizeof(tag));
    double payload = *(const godot_float *)value;
    memcpy(payload_of((godot_Variant *)r), &payload, sizeof(payload));
    g_variant_creates++;
}
static void ctor_bool(GDExtensionUninitializedVariantPtr r, GDExtensionTypePtr value) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_BOOL;
    memcpy(r, &tag, sizeof(tag));
    int64_t payload = *(const godot_bool *)value ? 1 : 0;
    memcpy(payload_of((godot_Variant *)r), &payload, sizeof(payload));
    g_variant_creates++;
}
static void ctor_string_name(GDExtensionUninitializedVariantPtr r, GDExtensionTypePtr value) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_STRING_NAME;
    memcpy(r, &tag, sizeof(tag));
    char *stored = NULL;
    memcpy(&stored, value, sizeof(stored));
    char *copy = fake_mem_alloc(strlen(stored) + 1);
    strcpy(copy, stored);
    memcpy(payload_of((godot_Variant *)r), &copy, sizeof(copy));
    g_variant_creates++;
}
static void ctor_vector3(GDExtensionUninitializedVariantPtr r, GDExtensionTypePtr value) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_VECTOR3;
    memcpy(r, &tag, sizeof(tag));
    const godot_Vector3 *v = value;
    float *copy = fake_mem_alloc(sizeof(float) * 3);
    copy[0] = v->coord[0];
    copy[1] = v->coord[1];
    copy[2] = v->coord[2];
    memcpy(payload_of((godot_Variant *)r), &copy, sizeof(copy));
    g_variant_creates++;
}
static void ctor_object(GDExtensionUninitializedVariantPtr r, GDExtensionTypePtr value) {
    memset(r, 0, sizeof(godot_Variant));
    int32_t tag = GDEXTENSION_VARIANT_TYPE_OBJECT;
    memcpy(r, &tag, sizeof(tag));
    // FROM_OBJECT hands over a pointer to the godot_Object pointer.
    void *object = NULL;
    memcpy(&object, value, sizeof(object));
    memcpy(payload_of((godot_Variant *)r), &object, sizeof(object));
    g_variant_creates++;
}

static GDExtensionVariantFromTypeConstructorFunc fake_variant_from_type_ctor(GDExtensionVariantType type) {
    switch (type) {
        case GDEXTENSION_VARIANT_TYPE_INT: return ctor_int;
        case GDEXTENSION_VARIANT_TYPE_FLOAT: return ctor_float;
        case GDEXTENSION_VARIANT_TYPE_BOOL: return ctor_bool;
        case GDEXTENSION_VARIANT_TYPE_STRING_NAME: return ctor_string_name;
        case GDEXTENSION_VARIANT_TYPE_VECTOR3: return ctor_vector3;
        case GDEXTENSION_VARIANT_TYPE_OBJECT: return ctor_object;
        default: fail("unexpected from-type constructor request"); return NULL;
    }
}
static GDExtensionPtrDestructor fake_variant_get_ptr_destructor(GDExtensionVariantType type) {
    if (type == GDEXTENSION_VARIANT_TYPE_STRING_NAME) return fake_string_name_destroy;
    fail("unexpected destructor request");
    return NULL;
}

// ---------- fake Object.call method bind ----------
static GDExtensionMethodBindPtr fake_classdb_get_method_bind(
        GDExtensionConstStringNamePtr klass, GDExtensionConstStringNamePtr method, GDExtensionInt hash) {
    (void)klass; (void)method; (void)hash;
    static int sentinel;
    return &sentinel;
}
static void append_arg_text(char *out, size_t cap, const godot_Variant *arg) {
    int64_t scalar = 0;
    memcpy(&scalar, payload_of_const(arg), sizeof(scalar));
    switch (tag_of(arg)) {
        case GDEXTENSION_VARIANT_TYPE_INT:
            snprintf(out + strlen(out), cap - strlen(out), "INT:%lld", (long long)scalar);
            return;
        case GDEXTENSION_VARIANT_TYPE_FLOAT: {
            double d = 0;
            memcpy(&d, payload_of_const(arg), sizeof(d));
            snprintf(out + strlen(out), cap - strlen(out), "FLOAT:%.1f", d);
            return;
        }
        case GDEXTENSION_VARIANT_TYPE_BOOL:
            snprintf(out + strlen(out), cap - strlen(out), "BOOL:%lld", (long long)scalar);
            return;
        case GDEXTENSION_VARIANT_TYPE_STRING_NAME: {
            char *stored = NULL;
            memcpy(&stored, payload_of_const(arg), sizeof(stored));
            snprintf(out + strlen(out), cap - strlen(out), "SN:%s", stored);
            return;
        }
        case GDEXTENSION_VARIANT_TYPE_VECTOR3: {
            float *coords = NULL;
            memcpy(&coords, payload_of_const(arg), sizeof(coords));
            snprintf(out + strlen(out), cap - strlen(out), "V3:(%.1f,%.1f,%.1f)",
                    (double)coords[0], (double)coords[1], (double)coords[2]);
            return;
        }
        case GDEXTENSION_VARIANT_TYPE_OBJECT:
            snprintf(out + strlen(out), cap - strlen(out), "OBJ:%lx", (unsigned long)scalar);
            return;
        default:
            fail("unexpected argument tag");
            return;
    }
}
static void fake_object_method_bind_call(
        GDExtensionMethodBindPtr bind, GDExtensionObjectPtr instance,
        const GDExtensionConstVariantPtr *args, GDExtensionInt argc,
        GDExtensionUninitializedVariantPtr r_ret, GDExtensionCallError *r_error) {
    (void)bind; (void)instance;
    CHECK(argc >= 1, "the method name is always prepended as argument 0");
    const godot_Variant *method = (const godot_Variant *)args[0];
    CHECK(tag_of(method) == GDEXTENSION_VARIANT_TYPE_STRING_NAME, "argument 0 must be the method name");
    char *method_name = NULL;
    memcpy(&method_name, payload_of_const(method), sizeof(method_name));
    char line[512];
    snprintf(line, sizeof(line), "EV call argc=%lld m=%s", (long long)argc, method_name);
    for (GDExtensionInt i = 1; i < argc; i++) {
        size_t used = strlen(line);
        snprintf(line + used, sizeof(line) - used, " a%lld=", (long long)i);
        append_arg_text(line, sizeof(line), (const godot_Variant *)args[i]);
    }
    printf("%s\n", line);
    fflush(stdout);
    int32_t tag = GDEXTENSION_VARIANT_TYPE_INT;
    memset(r_ret, 0, sizeof(godot_Variant));
    memcpy(r_ret, &tag, sizeof(tag));
    int64_t payload = 777;
    memcpy((uint8_t *)r_ret + 8, &payload, sizeof(payload));
    g_variant_creates++;
    if (r_error != NULL) r_error->error = GDEXTENSION_CALL_OK;
}

static void fake_unused_interface(void) {
}
static GDExtensionInterfaceFunctionPtr fake_get_proc_address(const char *name) {
    if (strcmp(name, "mem_alloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_alloc;
    if (strcmp(name, "mem_realloc") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_realloc;
    if (strcmp(name, "mem_free") == 0) return (GDExtensionInterfaceFunctionPtr)fake_mem_free;
    if (strcmp(name, "print_error") == 0) return (GDExtensionInterfaceFunctionPtr)fake_print_error;
    if (strcmp(name, "variant_new_nil") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_new_nil;
    if (strcmp(name, "variant_destroy") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_destroy;
    if (strcmp(name, "string_name_new_with_utf8_chars") == 0) return (GDExtensionInterfaceFunctionPtr)fake_string_name_new_with_utf8_chars;
    if (strcmp(name, "get_variant_from_type_constructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_from_type_ctor;
    if (strcmp(name, "variant_get_ptr_destructor") == 0) return (GDExtensionInterfaceFunctionPtr)fake_variant_get_ptr_destructor;
    if (strcmp(name, "classdb_get_method_bind") == 0) return (GDExtensionInterfaceFunctionPtr)fake_classdb_get_method_bind;
    if (strcmp(name, "object_method_bind_call") == 0) return (GDExtensionInterfaceFunctionPtr)fake_object_method_bind_call;
    return (GDExtensionInterfaceFunctionPtr)fake_unused_interface;
}

static godot_StringName method_name(const char *text) {
    godot_StringName name;
    godot_string_name_new_with_utf8_chars((GDExtensionUninitializedStringNamePtr)&name, text);
    return name;
}
static void expect_ret_777(const godot_Variant *ret) {
    CHECK(tag_of(ret) == GDEXTENSION_VARIANT_TYPE_INT, "return tag must be INT");
    int64_t payload = 0;
    memcpy(&payload, payload_of_const(ret), sizeof(payload));
    CHECK(payload == 777, "return payload must round-trip");
    printf("EV ret=INT:777\n");
    fflush(stdout);
}

int main(void) {
    godot_initialize_interface(fake_get_proc_address);
    godot_Object *fake_object = (godot_Object *)(uintptr_t)0xABC123u;

    godot_StringName ping = method_name("ping");
    godot_Variant r0 = GD_OBJECT_CALL0(fake_object, &ping);
    expect_ret_777(&r0);
    godot_Variant_destroy(&r0);
    godot_StringName_destroy(&ping);

    godot_StringName add_score = method_name("add_score");
    int large_before = g_large_allocs;
    // A bool VARIABLE, not the `true` literal: C23 makes `true` a bool keyword while
    // C11's stdbool.h keeps it an int macro, so a bare literal would _Generic-dispatch
    // differently between the two language standards. Generated dynamic calls pack
    // their arguments into Variants explicitly instead of using these helper macros;
    // this probe exercises the standalone GD_OBJECT_CALL* helpers.
    bool flag = true;
    godot_Variant r1 = GD_OBJECT_CALL3(fake_object, &add_score, 41, 2.5, flag);
    printf("EV scalar_call_large_allocs=%d\n", g_large_allocs - large_before);
    fflush(stdout);
    expect_ret_777(&r1);
    godot_Variant_destroy(&r1);
    godot_StringName_destroy(&add_score);

    godot_StringName mix = method_name("mix");
    godot_StringName target = method_name("Target");
    godot_Vector3 vec;
    vec.coord[0] = 1.0f;
    vec.coord[1] = 2.0f;
    vec.coord[2] = 3.0f;
    godot_Variant r2 = GD_OBJECT_CALL3(fake_object, &mix, &target, &vec, fake_object);
    expect_ret_777(&r2);
    godot_Variant_destroy(&r2);
    godot_StringName_destroy(&mix);
    godot_StringName_destroy(&target);

    printf("EV variant_balance=%s\n", g_variant_creates == g_variant_destroys ? "ok" : "BROKEN");
    printf("EV mem_balance=%d\n", g_mem_balance);
    printf("EV errors=%d\n", g_error_count);
    fflush(stdout);
    if (g_variant_creates != g_variant_destroys || g_mem_balance != 0 || g_error_count != 0) return 10;
    printf("OK dynamic_call\n");
    return 0;
}
