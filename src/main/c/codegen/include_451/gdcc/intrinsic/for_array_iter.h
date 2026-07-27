#ifndef GDCC_INTRINSIC_FOR_ARRAY_ITER_H
#define GDCC_INTRINSIC_FOR_ARRAY_ITER_H

#include <godot_binding.h>

/// Index-based Array iterator.
/// Array is reference-semantic (shared `_p`, mutations do not detach), so this state keeps only a
/// shared Array handle + cached size and resolves each element via `operator_index_const` at get
/// time (godot-cpp-style direct element address, without `godot_Array_get` method dispatch).
/// Caching a raw base pointer would dangle if the iterated array is resized during the loop.
typedef struct gdcc_for_array_iter {
    godot_Array source;
    godot_int index;
    godot_int size;
} gdcc_for_array_iter;

static inline gdcc_for_array_iter gdcc_for_array_iter_init(void) {
    return (gdcc_for_array_iter){
        .source = godot_new_Array(),
        .index = 0,
        .size = 0,
    };
}

static inline void gdcc_for_array_iter_destroy(gdcc_for_array_iter *state) {
    godot_Array_destroy(&state->source);
}

static inline gdcc_for_array_iter gdcc_for_array_iter_copy(const gdcc_for_array_iter *src) {
    return (gdcc_for_array_iter){
        .source = godot_new_Array_with_Array(&src->source),
        .index = src->index,
        .size = src->size,
    };
}

static inline gdcc_for_array_iter gdcc_for_array_iter_from_array(const godot_Array *source) {
    return (gdcc_for_array_iter){
        .source = godot_new_Array_with_Array(source),
        .index = 0,
        .size = godot_Array_size(source),
    };
}

static inline godot_bool gdcc_for_array_iter_should_continue(const gdcc_for_array_iter *state) {
    return state->index < state->size;
}

/// Advances the iterator by one position. The source Array handle is shared (refcount +1).
static inline gdcc_for_array_iter gdcc_for_array_iter_next(const gdcc_for_array_iter *state) {
    return (gdcc_for_array_iter){
        .source = godot_new_Array_with_Array(&state->source),
        .index = state->index + 1,
        .size = state->size,
    };
}

/// Returns an owned Variant copy of the current element via operator_index_const (no Array_get).
static inline godot_Variant gdcc_for_array_iter_get(const gdcc_for_array_iter *state) {
    if (state->index < 0 || state->index >= state->size) {
        return godot_new_Variant_nil();
    }
    const godot_Variant *elem =
        (const godot_Variant *)godot_array_operator_index_const(&state->source, state->index);
    if (elem == NULL) {
        return godot_new_Variant_nil();
    }
    return godot_new_Variant_with_Variant(elem);
}

#endif
