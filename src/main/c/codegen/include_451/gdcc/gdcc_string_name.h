#ifndef GDCC_STRING_NAME_H
#define GDCC_STRING_NAME_H

#include <godot_binding.h>
#include "gdcc_likely.h"

// Generation stamp sentinel for GD_STATIC_SN / GD_STATIC_S expansion sites. Function-local statics
// start at this value so a fresh image (registry generation 0) always mismatches and initializes.
// destroy_all skips over it when wrapping, so it never aliases a real generation.
#ifndef GDCC_REGISTRY_GEN_NEVER
#define GDCC_REGISTRY_GEN_NEVER UINT64_MAX
#endif

typedef struct StringNameDestroyRegistry {
    godot_StringName** items;
    uint32_t count;
    uint32_t capacity;
    // Bumped by destroy_all. On same-image reload (e.g. macOS dyld reusing a dlclose'd path) the
    // function-local statics of GD_STATIC_SN survive while every StringName is destroyed here, so
    // the macros gate re-construction on a generation mismatch instead of init-once booleans that
    // would stay stuck at true and hand back destroyed values.
    uint64_t generation;
} StringNameDestroyRegistry;

typedef struct gdcc_StringNameWithHash {
    godot_StringName* name;
    godot_int hash;
} gdcc_StringNameWithHash;

static StringNameDestroyRegistry g_sn_registry = {nullptr};

static void gdcc_sn_registry_add(godot_StringName* p_sn) {
    // No deduplication: each expansion site registers once per generation.
    if (g_sn_registry.count == g_sn_registry.capacity) {
        const uint32_t new_cap = (g_sn_registry.capacity == 0) ? 128u : (g_sn_registry.capacity * 2u);
        const size_t new_size = (size_t)new_cap * sizeof(godot_StringName*);
        if (g_sn_registry.items == NULL) {
            g_sn_registry.items = (godot_StringName**)godot_mem_alloc(new_size);
        } else {
            g_sn_registry.items = (godot_StringName**)godot_mem_realloc(g_sn_registry.items, new_size);
        }
        g_sn_registry.capacity = new_cap;
    }
    g_sn_registry.items[g_sn_registry.count++] = p_sn;
}

static void gdcc_sn_registry_destroy_all(void) {
    for (uint32_t i = 0; i < g_sn_registry.count; ++i) {
        godot_StringName_destroy(g_sn_registry.items[i]);
    }
    if (g_sn_registry.items != NULL) {
        godot_mem_free(g_sn_registry.items);
    }
    g_sn_registry.items = NULL;
    g_sn_registry.count = 0;
    g_sn_registry.capacity = 0;
    g_sn_registry.generation++;
    if (g_sn_registry.generation == GDCC_REGISTRY_GEN_NEVER) {
        // Reaching the sentinel needs 2^64 reloads; wrap so it can never alias a real generation.
        g_sn_registry.generation = 0;
    }
}

// Macro: In-place declaration + generation-gated (re)initialization + registration + return pointer.
// E.g. godot_StringName *name = GD_STATIC_SN(u8"_ready");
// Per-TU contract: expansions register into the TU-local g_sn_registry, so live calls are only
// allowed in the generated entry TU whose deinitialize() destroys that same registry copy. Runtime
// .c files (gdcc_hrx.c, gdcc_coroutine.c, ...) must NOT expand this macro or call helpers that do
// (gdcc_make_property, gdcc_bind_property) — their TU-local registry copy would leak on unload.
#define GD_STATIC_SN(U8_LIT)                                                       \
    ({                                                                             \
        static godot_StringName _gd_sn;                                            \
        static uint64_t _gd_sn_gen = GDCC_REGISTRY_GEN_NEVER;                      \
        if (unlikely(_gd_sn_gen != g_sn_registry.generation)) {                    \
            _gd_sn = godot_new_StringName_with_utf8_chars((const char*)(U8_LIT));  \
            gdcc_sn_registry_add(&_gd_sn);                                         \
            _gd_sn_gen = g_sn_registry.generation;                                 \
        }                                                                          \
        &_gd_sn;                                                                   \
    })

// The hash cache needs its own generation stamp: a statement expression cannot read the inner
// GD_STATIC_SN expansion's function-local static, and the hash must be recomputed whenever the
// StringName was rebuilt for a new generation.
#define GD_STATIC_SN_HASH(U8_LIT)                                                   \
    ({                                                                              \
        static godot_int _gd_sn_hash = 0;                                           \
        static uint64_t _gd_sn_hash_gen = GDCC_REGISTRY_GEN_NEVER;                  \
        godot_StringName *_gd_sn_ptr = GD_STATIC_SN((const char*)(U8_LIT));         \
        if (unlikely(_gd_sn_hash_gen != g_sn_registry.generation)) {                \
            _gd_sn_hash = godot_StringName_hash(_gd_sn_ptr);                        \
            _gd_sn_hash_gen = g_sn_registry.generation;                             \
        }                                                                           \
        (gdcc_StringNameWithHash){ .name = _gd_sn_ptr, .hash = _gd_sn_hash };       \
    })


#endif //GDCC_STRING_NAME_H
