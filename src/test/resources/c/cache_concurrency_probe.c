#if defined(_WIN32)
#include <windows.h>
#else
#include <pthread.h>
#endif

static GDExtensionClassLibraryPtr class_library = NULL;
#include <gdcc_bind.h>

#define THREAD_COUNT 4
#define SITE_COUNT 300
#define GENERATIONS 4

static _Atomic(int) lookup_count;
static _Atomic(int) builtin_lookups;
static _Atomic(int) utility_lookups;
static _Atomic(int) singleton_lookups;
static _Atomic(int) fixed_lookups;
static char binding_sentinel;
static char fixed_sentinel;
static char engine_sentinel;
static int failures[THREAD_COUNT];
static const void *name_addresses[THREAD_COUNT][SITE_COUNT];
static const void *string_addresses[THREAD_COUNT][SITE_COUNT];

// Keep each read-only lookup in flight until every thread has observed an empty cache.
static void cold_lookup_gate(_Atomic(int) *count) {
    atomic_fetch_add_explicit(count, 1, memory_order_release);
    while (atomic_load_explicit(count, memory_order_acquire) < THREAD_COUNT) {
    }
}

static GDExtensionMethodBindPtr lookup_binding(GDExtensionConstStringNamePtr owner,
        GDExtensionConstStringNamePtr method, GDExtensionInt hash) {
    if (strcmp(fake_utf8_of_sn(owner), "Engine") == 0
            && strcmp(fake_utf8_of_sn(method), "is_editor_hint") == 0) {
        cold_lookup_gate(&fixed_lookups);
        return (GDExtensionMethodBindPtr)&fixed_sentinel;
    }
    CHECK(strcmp(fake_utf8_of_sn(owner), "Object") == 0, "wrong binding owner");
    CHECK(strcmp(fake_utf8_of_sn(method), "probe") == 0, "wrong binding method");
    atomic_fetch_add_explicit(&lookup_count, 1, memory_order_relaxed);
    return hash == 22 ? (GDExtensionMethodBindPtr)&binding_sentinel : NULL;
}

static void vector_length(GDExtensionTypePtr self, const GDExtensionConstTypePtr *args,
        GDExtensionTypePtr result, int count) {
    *(godot_float *)result = 3.5;
}

static GDExtensionPtrBuiltInMethod lookup_builtin(GDExtensionVariantType type,
        GDExtensionConstStringNamePtr method, GDExtensionInt hash) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR2 && strcmp(fake_utf8_of_sn(method), "length") == 0) {
        cold_lookup_gate(&builtin_lookups);
        return vector_length;
    }
    return fake_variant_get_ptr_builtin_method(type, method, hash);
}

static void minimum(GDExtensionTypePtr result, const GDExtensionConstTypePtr *args, int count) {
    godot_int a = *(const godot_int *)args[0];
    godot_int b = *(const godot_int *)args[1];
    *(godot_int *)result = a < b ? a : b;
}

static GDExtensionPtrUtilityFunction lookup_utility(GDExtensionConstStringNamePtr name, GDExtensionInt hash) {
    CHECK(strcmp(fake_utf8_of_sn(name), "mini") == 0, "wrong utility name");
    cold_lookup_gate(&utility_lookups);
    return minimum;
}

static GDExtensionObjectPtr lookup_singleton(GDExtensionConstStringNamePtr name) {
    CHECK(strcmp(fake_utf8_of_sn(name), "Engine") == 0, "wrong singleton name");
    cold_lookup_gate(&singleton_lookups);
    return &engine_sentinel;
}

static void fixed_ptrcall(GDExtensionMethodBindPtr binding, GDExtensionObjectPtr self,
        const GDExtensionConstTypePtr *args, GDExtensionTypePtr result) {
    CHECK(binding == (GDExtensionMethodBindPtr)&fixed_sentinel && self == &engine_sentinel, "wrong fixed binding");
    *(godot_bool *)result = true;
}

static GDExtensionInterfaceFunctionPtr cache_get_proc_address(const char *name) {
    if (strcmp(name, "classdb_get_method_bind") == 0) {
        return (GDExtensionInterfaceFunctionPtr)lookup_binding;
    }
    if (strcmp(name, "variant_get_ptr_builtin_method") == 0) return (GDExtensionInterfaceFunctionPtr)lookup_builtin;
    if (strcmp(name, "variant_get_ptr_utility_function") == 0) return (GDExtensionInterfaceFunctionPtr)lookup_utility;
    if (strcmp(name, "global_get_singleton") == 0) return (GDExtensionInterfaceFunctionPtr)lookup_singleton;
    if (strcmp(name, "object_method_bind_ptrcall") == 0) return (GDExtensionInterfaceFunctionPtr)fixed_ptrcall;
    return fake_get_proc_address(name);
}

GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(probe_bind,
        u8"Object", u8"probe", "Object", "probe", 11, 1, 22)

typedef struct CacheResult {
    godot_String *text;
    godot_StringName *name;
    godot_int hash;
} CacheResult;

static CacheResult cache_site(int site) {
    switch (site) {
#define CACHE_SITE(N) case N: { \
        godot_String *text = GD_STATIC_S(u8"site-" #N); \
        gdcc_StringNameWithHash name = GD_STATIC_SN_HASH(u8"site-" #N); \
        return (CacheResult){text, name.name, name.hash}; \
    }
/* CACHE_SITES */
#undef CACHE_SITE
    }
    fail("invalid cache site");
    return (CacheResult){0};
}

// All threads leave this gate together, before the first cache access of each generation.
#if defined(_WIN32)
static HANDLE start_event;
#else
static pthread_mutex_t start_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t start_condition = PTHREAD_COND_INITIALIZER;
static int started;
#endif

static void run_worker(int index) {
#if defined(_WIN32)
    CHECK(WaitForSingleObject(start_event, INFINITE) == WAIT_OBJECT_0, "start wait failed");
#else
    pthread_mutex_lock(&start_mutex);
    while (!started) pthread_cond_wait(&start_condition, &start_mutex);
    pthread_mutex_unlock(&start_mutex);
#endif
    godot_Vector2 vector = {0};
    if (godot_Vector2_length(&vector) != 3.5) failures[index]++;
    if (godot_mini(42, 7) != 7) failures[index]++;
    if (!godot_Engine_is_editor_hint(godot_Engine_singleton())) failures[index]++;
    GDExtensionMethodBindPtr binding = NULL;
    if (!probe_bind(&binding) || binding != (GDExtensionMethodBindPtr)&binding_sentinel) failures[index]++;
    for (int round = 0; round < 3; round++) {
        for (int n = 0; n < SITE_COUNT; n++) {
            int site = n == 0 ? 0 : 1 + (n - 1 + index * 17) % (SITE_COUNT - 1);
            CacheResult result = cache_site(site);
            char expected[32];
            snprintf(expected, sizeof(expected), "site-%d", site);
            if (strcmp(fake_utf8_of_s(result.text), expected) != 0
                    || strcmp(fake_utf8_of_sn(result.name), expected) != 0
                    || result.hash != fake_fnv1a(expected)) failures[index]++;
            name_addresses[index][site] = result.name;
            string_addresses[index][site] = result.text;
        }
    }
}

#if defined(_WIN32)
static DWORD WINAPI thread_entry(LPVOID arg) {
    run_worker((int)(intptr_t)arg);
    return 0;
}
#else
static void *thread_entry(void *arg) {
    run_worker((int)(intptr_t)arg);
    return NULL;
}
#endif

int main(void) {
    CHECK(godot_initialize_interface(cache_get_proc_address) != 0, "interface init failed");
    for (int generation = 0; generation < GENERATIONS; generation++) {
#if defined(_WIN32)
        HANDLE threads[THREAD_COUNT];
        start_event = CreateEvent(NULL, TRUE, FALSE, NULL);
        CHECK(start_event != NULL, "event creation failed");
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = CreateThread(NULL, 0, thread_entry, (LPVOID)(intptr_t)i, 0, NULL);
            CHECK(threads[i] != NULL, "thread creation failed");
        }
        CHECK(SetEvent(start_event), "start signal failed");
        CHECK(WaitForMultipleObjects(THREAD_COUNT, threads, TRUE, INFINITE) == WAIT_OBJECT_0, "join failed");
        for (int i = 0; i < THREAD_COUNT; i++) CloseHandle(threads[i]);
        CloseHandle(start_event);
#else
        pthread_t threads[THREAD_COUNT];
        started = 0;
        for (int i = 0; i < THREAD_COUNT; i++) {
            CHECK(pthread_create(&threads[i], NULL, thread_entry, (void *)(intptr_t)i) == 0, "thread creation failed");
        }
        pthread_mutex_lock(&start_mutex);
        started = 1;
        pthread_cond_broadcast(&start_condition);
        pthread_mutex_unlock(&start_mutex);
        for (int i = 0; i < THREAD_COUNT; i++) CHECK(pthread_join(threads[i], NULL) == 0, "join failed");
#endif
        for (int i = 0; i < THREAD_COUNT; i++) {
            CHECK(failures[i] == 0, "invalid cache value or binding");
            for (int site = 0; site < SITE_COUNT; site++) {
                CHECK(name_addresses[i][site] == name_addresses[0][site], "StringName storage is not shared");
                CHECK(string_addresses[i][site] == string_addresses[0][site], "String storage is not shared");
            }
        }
        CHECK(g_n_registry.count == SITE_COUNT, "String registration was lost or duplicated");
        CHECK(g_sn_registry.count == SITE_COUNT + (generation == 0 ? 4 : 0), "StringName registration was lost or duplicated");
        CHECK(g_n_registry.capacity >= SITE_COUNT && g_sn_registry.capacity >= SITE_COUNT, "registry did not grow");
        CHECK(g_sn_hash_invoke == SITE_COUNT * (generation + 1), "hash was computed more than once per generation");
        CHECK(g_sn_from_string_construct == SITE_COUNT * (generation + 1) + 4, "StringName was constructed more than once");
        if (g_s_utf8_construct != 2 * SITE_COUNT * (generation + 1) + 4) {
            printf("String count actual=%llu expected=%d generation=%d balance=%d\n",
                    (unsigned long long)g_s_utf8_construct, 2 * SITE_COUNT * (generation + 1) + 4,
                    generation, (int)g_mem_balance);
        }
        CHECK(g_s_utf8_construct == 2 * SITE_COUNT * (generation + 1) + 4, "String was constructed more than once");
        CHECK(lookup_count >= 2 && lookup_count <= THREAD_COUNT * 2, "unexpected lookup retry count");
        CHECK(builtin_lookups == THREAD_COUNT && utility_lookups == THREAD_COUNT
                && singleton_lookups == THREAD_COUNT && fixed_lookups == THREAD_COUNT,
                "cold duplicate lookup or warm cache behavior is wrong");
        int lookups = lookup_count;
        for (int i = 0; i < 100; i++) {
            GDExtensionMethodBindPtr binding;
            CHECK(probe_bind(&binding), "cached binding lookup failed");
        }
        CHECK(lookup_count == lookups, "warm binding cache repeated a lookup");
        gdcc_sn_registry_destroy_all();
        gdcc_s_registry_destroy_all();
        CHECK(g_sn_destruct == g_sn_from_string_construct + g_sn_raw_utf8_construct, "StringName cleanup mismatch");
        CHECK(g_s_destruct == g_s_utf8_construct && g_mem_balance == 0, "String cleanup mismatch or leak");
    }
    atomic_store(&g_sn_registry.generation, GDCC_REGISTRY_GEN_NEVER - 1);
    atomic_store(&g_n_registry.generation, GDCC_REGISTRY_GEN_NEVER - 1);
    gdcc_sn_registry_destroy_all();
    gdcc_s_registry_destroy_all();
    CHECK(g_sn_registry.generation == 0 && g_n_registry.generation == 0, "generation sentinel did not wrap");
    printf("OK concurrent caches\n");
    return 0;
}
