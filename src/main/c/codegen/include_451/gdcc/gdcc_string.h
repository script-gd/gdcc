#ifndef GDCC_STRING_H
#define GDCC_STRING_H

#include <gdextension-lite.h>
#include "gdcc_likely.h"

typedef struct StringDestroyRegistry {
    godot_String** items;
    uint32_t count;
    uint32_t capacity;
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
}

// Macro: In-place declaration + first initialization + registration + return pointer
// E.g. godot_String *name = GD_STATIC_S(u8"_ready");
#define GD_STATIC_S(U8_LIT)                                                       \
    ({                                                                            \
        static godot_String _gd_s;                                                \
        static bool _gd_s_inited = false;                                         \
        static bool _gd_s_registered = false;                                     \
        if (unlikely(!_gd_s_inited)) {                                            \
            _gd_s = godot_new_String_with_utf8_chars((U8_LIT));                   \
            _gd_s_inited = true;                                                  \
        }                                                                         \
        if (unlikely(!_gd_s_registered)) {                                        \
            gdcc_s_registry_add(&_gd_s);                                          \
            _gd_s_registered = true;                                              \
        }                                                                         \
        &_gd_s;                                                                   \
    })



#endif //GDCC_STRING_H
