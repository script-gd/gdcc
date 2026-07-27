#ifndef GDCC_INTRINSIC_FOR_RANGE_ITER_H
#define GDCC_INTRINSIC_FOR_RANGE_ITER_H

#include <godot_binding.h>
#include "../gdcc_likely.h"

typedef struct gdcc_for_range_iter {
    godot_int current;
    godot_int end;
    godot_int step;
} gdcc_for_range_iter;

static inline gdcc_for_range_iter gdcc_for_range_iter_init(void) {
    return (gdcc_for_range_iter){ .current = 0, .end = 0, .step = 1 };
}

static inline void gdcc_for_range_iter_destroy(gdcc_for_range_iter *iter) {
    (void)iter;
}

static inline gdcc_for_range_iter gdcc_for_range_iter_from_bounds(
    godot_int start,
    godot_int end,
    godot_int step
) {
    return (gdcc_for_range_iter){ .current = start, .end = end, .step = step };
}

static inline godot_bool gdcc_for_range_iter_should_continue(const gdcc_for_range_iter *iter) {
    // Godot's optimized range loop treats a zero step as an empty range.
    if (unlikely(iter->step == 0)) {
        return false;
    }
    if (iter->step > 0) {
        return iter->current < iter->end;
    }
    return iter->current > iter->end;
}

/// Unprotected int64 addition, matching Godot 4.5.1 OPCODE_ITERATE_RANGE (`*count += step`).
/// Overflow wraps and may cause an infinite loop; this is intentional for upstream compatibility.
static inline gdcc_for_range_iter gdcc_for_range_iter_next(const gdcc_for_range_iter *iter) {
    return (gdcc_for_range_iter){
        .current = iter->current + iter->step,
        .end = iter->end,
        .step = iter->step,
    };
}

static inline godot_int gdcc_for_range_iter_get(const gdcc_for_range_iter *iter) {
    return iter->current;
}

#endif
