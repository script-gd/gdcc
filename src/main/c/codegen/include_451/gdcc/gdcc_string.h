#ifndef GDCC_STRING_H
#define GDCC_STRING_H

#include <godot_binding.h>
#include "gdcc_likely.h"

// See gdcc_string_name.h for the generation-sentinel rationale; guarded because both headers are
// usually included into the same TU.
#ifndef GDCC_REGISTRY_GEN_NEVER
#define GDCC_REGISTRY_GEN_NEVER UINT64_MAX
#endif

typedef struct StringDestroyRegistry {
    godot_String** items;
    uint32_t count;
    uint32_t capacity;
    // Bumped by destroy_all so GD_STATIC_S expansion sites rebuild destroyed values after a
    // same-image reload; see gdcc_string_name.h.
    uint64_t generation;
} StringDestroyRegistry;


static StringDestroyRegistry g_n_registry = {nullptr};

static void gdcc_s_registry_add(godot_String* p_sn) {
    // No deduplication, relying on macro logic of "only once per static object"
    if (g_n_registry.count == g_n_registry.capacity) {
        const uint32_t new_cap = (g_n_registry.capacity == 0) ? 128u : (g_n_registry.capacity * 2u);
        const size_t new_size = (size_t)new_cap * sizeof(godot_String*);
        if (g_n_registry.items == NULL) {
            g_n_registry.items = (godot_String**)godot_mem_alloc(new_size);
        } else {
            g_n_registry.items = (godot_String**)godot_mem_realloc(g_n_registry.items, new_size);
        }
        g_n_registry.capacity = new_cap;
    }
    g_n_registry.items[g_n_registry.count++] = p_sn;
}

static void gdcc_s_registry_destroy_all(void) {
    for (uint32_t i = 0; i < g_n_registry.count; ++i) {
        godot_String_destroy(g_n_registry.items[i]);
    }
    if (g_n_registry.items != NULL) {
        godot_mem_free(g_n_registry.items);
    }
    g_n_registry.items = NULL;
    g_n_registry.count = 0;
    g_n_registry.capacity = 0;
    g_n_registry.generation++;
    if (g_n_registry.generation == GDCC_REGISTRY_GEN_NEVER) {
        // Reaching the sentinel needs 2^64 reloads; wrap so it can never alias a real generation.
        g_n_registry.generation = 0;
    }
}

// Macro: In-place declaration + generation-gated (re)initialization + registration + return pointer.
// E.g. godot_String *name = GD_STATIC_S(u8"_ready");
// Same per-TU contract as GD_STATIC_SN (gdcc_string_name.h): generated entry TU only, never from
// runtime .c files.
#define GD_STATIC_S(U8_LIT)                                                       \
    ({                                                                            \
        static godot_String _gd_s;                                                \
        static uint64_t _gd_s_gen = GDCC_REGISTRY_GEN_NEVER;                      \
        if (unlikely(_gd_s_gen != g_n_registry.generation)) {                     \
            _gd_s = godot_new_String_with_utf8_chars((const char*)(U8_LIT));      \
            gdcc_s_registry_add(&_gd_s);                                          \
            _gd_s_gen = g_n_registry.generation;                                  \
        }                                                                         \
        &_gd_s;                                                                   \
    })



#endif //GDCC_STRING_H
