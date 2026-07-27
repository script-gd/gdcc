#ifndef GDCC_INTRINSIC_FOR_FLOAT_ITER_H
#define GDCC_INTRINSIC_FOR_FLOAT_ITER_H

#include <godot_binding.h>

typedef struct gdcc_for_float_iter {
    godot_float current;
    godot_float end;
} gdcc_for_float_iter;

static inline gdcc_for_float_iter gdcc_for_float_iter_init(void) {
    return (gdcc_for_float_iter){ .current = 0.0, .end = 0.0 };
}

static inline void gdcc_for_float_iter_destroy(gdcc_for_float_iter *iter) {
    (void)iter;
}

/// Matches Godot `Variant::iter_init` for FLOAT: start at 0.0 and iterate while current < end.
static inline gdcc_for_float_iter gdcc_for_float_iter_from_end(godot_float end) {
    return (gdcc_for_float_iter){ .current = 0.0, .end = end };
}

static inline godot_bool gdcc_for_float_iter_should_continue(const gdcc_for_float_iter *iter) {
    return iter->current < iter->end;
}

static inline gdcc_for_float_iter gdcc_for_float_iter_next(const gdcc_for_float_iter *iter) {
    return (gdcc_for_float_iter){
        .current = iter->current + 1.0,
        .end = iter->end,
    };
}

static inline godot_float gdcc_for_float_iter_get(const gdcc_for_float_iter *iter) {
    return iter->current;
}

#endif
