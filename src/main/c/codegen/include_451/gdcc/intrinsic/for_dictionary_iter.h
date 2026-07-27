#ifndef GDCC_INTRINSIC_FOR_DICTIONARY_ITER_H
#define GDCC_INTRINSIC_FOR_DICTIONARY_ITER_H

#include <godot_binding.h>

/// Heap-shared keys snapshot for dictionary key iteration.
/// Owned by one or more `gdcc_for_dictionary_iter` values via non-atomic `refcount`.
/// For-iterator locals are single-threaded (no cross-thread sharing).
typedef struct gdcc_for_dictionary_iter_box {
    godot_int refcount;
    godot_Array keys;
} gdcc_for_dictionary_iter_box;

/// Dictionary key iteration state: shared heap box + cached element base pointer + index.
/// `next`/`copy` only bump `box->refcount` (no per-step `godot_new_Array_with_Array`).
/// Last `destroy` drops the box and destroys the keys Array.
typedef struct gdcc_for_dictionary_iter {
    gdcc_for_dictionary_iter_box *box;
    const godot_Variant *ptr;
    godot_int index;
    godot_int size;
} gdcc_for_dictionary_iter;

static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_init(void) {
    return (gdcc_for_dictionary_iter){
        .box = NULL,
        .ptr = NULL,
        .index = 0,
        .size = 0,
    };
}

static inline void gdcc_for_dictionary_iter_destroy(gdcc_for_dictionary_iter *state) {
    if (state->box != NULL) {
        state->box->refcount -= 1;
        if (state->box->refcount <= 0) {
            godot_Array_destroy(&state->box->keys);
            godot_mem_free(state->box);
        }
        state->box = NULL;
    }
    state->ptr = NULL;
    state->index = 0;
    state->size = 0;
}

static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_copy(const gdcc_for_dictionary_iter *src) {
    if (src->box != NULL) {
        src->box->refcount += 1;
    }
    return (gdcc_for_dictionary_iter){
        .box = src->box,
        .ptr = src->ptr,
        .index = src->index,
        .size = src->size,
    };
}

/// Initializes dictionary key iteration by extracting all keys into a heap-shared Array box.
/// Godot Dictionary iteration yields keys; this snapshot approach avoids cursor invalidation
/// if the dictionary is mutated during iteration. Note: Godot VM uses a live cursor that
/// invalidates on mutation; this snapshot is a deliberate safer divergence.
static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_from_dictionary(
    const godot_Dictionary *source
) {
    gdcc_for_dictionary_iter_box *box =
        (gdcc_for_dictionary_iter_box *)godot_mem_alloc(sizeof(gdcc_for_dictionary_iter_box));
    box->refcount = 1;
    box->keys = godot_Dictionary_keys(source);
    godot_int size = godot_Array_size(&box->keys);
    const godot_Variant *ptr = NULL;
    if (size > 0) {
        // Contiguous Variant storage of the independent keys snapshot. The box owns this
        // snapshot exclusively and every iterator op is read-only on it, so the buffer never
        // reallocates and the cached base stays valid (unlike for_array_iter, which iterates
        // a user Array that may be resized and therefore does not cache a base pointer).
        ptr = (const godot_Variant *)godot_array_operator_index_const(&box->keys, 0);
    }
    return (gdcc_for_dictionary_iter){
        .box = box,
        .ptr = ptr,
        .index = 0,
        .size = size,
    };
}

static inline godot_bool gdcc_for_dictionary_iter_should_continue(const gdcc_for_dictionary_iter *state) {
    return state->index < state->size;
}

/// Advances the iterator by one position. Shares the same heap box via non-atomic refcount.
static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_next(const gdcc_for_dictionary_iter *state) {
    if (state->box != NULL) {
        state->box->refcount += 1;
    }
    return (gdcc_for_dictionary_iter){
        .box = state->box,
        .ptr = state->ptr,
        .index = state->index + 1,
        .size = state->size,
    };
}

static inline godot_Variant gdcc_for_dictionary_iter_get(const gdcc_for_dictionary_iter *state) {
    if (state->ptr == NULL || state->index < 0 || state->index >= state->size) {
        return godot_new_Variant_nil();
    }
    return godot_new_Variant_with_Variant(&state->ptr[state->index]);
}

#endif
