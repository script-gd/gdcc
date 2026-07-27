#ifndef GDCC_INTRINSIC_FOR_STRING_ITER_H
#define GDCC_INTRINSIC_FOR_STRING_ITER_H

#include <godot_binding.h>

typedef struct gdcc_for_string_iter {
    godot_String source;
    godot_int index;
    godot_int length;
} gdcc_for_string_iter;

static inline gdcc_for_string_iter gdcc_for_string_iter_init(void) {
    return (gdcc_for_string_iter){
        .source = godot_new_String(),
        .index = 0,
        .length = 0,
    };
}

static inline void gdcc_for_string_iter_destroy(gdcc_for_string_iter *state) {
    godot_String_destroy(&state->source);
}

static inline gdcc_for_string_iter gdcc_for_string_iter_copy(const gdcc_for_string_iter *src) {
    return (gdcc_for_string_iter){
        .source = godot_new_String_with_String(&src->source),
        .index = src->index,
        .length = src->length,
    };
}

static inline gdcc_for_string_iter gdcc_for_string_iter_from_string(const godot_String *source) {
    return (gdcc_for_string_iter){
        .source = godot_new_String_with_String(source),
        .index = 0,
        .length = godot_String_length(source),
    };
}

static inline godot_bool gdcc_for_string_iter_should_continue(const gdcc_for_string_iter *state) {
    return state->index < state->length;
}

/// Advances the iterator by one position. The source String is shared via COW
/// (copy-on-write), so this is O(1) — only the reference count is incremented.
static inline gdcc_for_string_iter gdcc_for_string_iter_next(const gdcc_for_string_iter *state) {
    return (gdcc_for_string_iter){
        .source = godot_new_String_with_String(&state->source),
        .index = state->index + 1,
        .length = state->length,
    };
}

/// Returns the current character as a single-character String, matching Godot's String iteration
/// semantics (`str.substr(iter, 1)` in `Variant::iter_get`).
static inline godot_String gdcc_for_string_iter_get(const gdcc_for_string_iter *state) {
    return godot_String_substr(&state->source, state->index, 1);
}

#endif
