#ifndef GDCC_STRING_NAME_H
#define GDCC_STRING_NAME_H

#include <gdextension-lite.h>
#include "gdcc_likely.h"

typedef struct StringNameDestroyRegistry {
    godot_StringName** items;
    uint32_t count;
    uint32_t capacity;
} StringNameDestroyRegistry;

typedef struct gdcc_StringNameWithHash {
    godot_StringName* name;
    godot_int hash;
} gdcc_StringNameWithHash;

static StringNameDestroyRegistry g_sn_registry = {nullptr};

static void gdcc_sn_registry_add(godot_StringName* p_sn) {
    // No deduplication, relying on macro logic of "only once per static object"
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
}

// Macro: In-place declaration + first initialization + registration + return pointer
// E.g. godot_StringName *name = GD_STATIC_SN(u8"_ready");
#define GD_STATIC_SN(U8_LIT)                                                       \
    ({                                                                             \
        static godot_StringName _gd_sn;                                            \
        static bool _gd_sn_inited = false;                                         \
        static bool _gd_sn_registered = false;                                     \
        if (unlikely(!_gd_sn_inited)) {                                            \
            _gd_sn = godot_new_StringName_with_utf8_chars((const char*)(U8_LIT));  \
            _gd_sn_inited = true;                                                  \
        }                                                                          \
        if (unlikely(!_gd_sn_registered)) {                                        \
            gdcc_sn_registry_add(&_gd_sn);                                         \
            _gd_sn_registered = true;                                              \
        }                                                                          \
        &_gd_sn;                                                                   \
    })

#define GD_STATIC_SN_HASH(U8_LIT)                                                   \
    ({                                                                              \
        static godot_int _gd_sn_hash = 0;                                           \
        static bool _gd_sn_hash_inited = false;                                     \
        godot_StringName *_gd_sn_ptr = GD_STATIC_SN((const char*)(U8_LIT));         \
        if (unlikely(!_gd_sn_hash_inited)) {                                        \
            _gd_sn_hash = godot_StringName_hash(_gd_sn_ptr);                        \
            _gd_sn_hash_inited = true;                                              \
        }                                                                           \
        (gdcc_StringNameWithHash){ .name = _gd_sn_ptr, .hash = _gd_sn_hash };       \
    })


#endif //GDCC_STRING_NAME_H
