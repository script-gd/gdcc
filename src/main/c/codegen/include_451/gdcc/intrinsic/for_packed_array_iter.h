#ifndef GDCC_INTRINSIC_FOR_PACKED_ARRAY_ITER_H
#define GDCC_INTRINSIC_FOR_PACKED_ARRAY_ITER_H

#include <godot_binding.h>
#include <gdcc_likely.h>
#include <gdcc_packed_ref.h>

/// Per-family Packed*Array for-in iterator helpers (LIVE iteration,
/// packed_array_reference_semantics_plan.md §4.3.8, §2 row 12).
///
/// Each Packed*Array has its own state struct and typed helpers so `get`/`copy`/`destroy`
/// need no runtime kind switch. The state holds a Variant holder COPY of the source array
/// (sharing its identity) plus the current index — deliberately no COW struct snapshot, no
/// cached size and no cached element base pointer:
/// - `should_continue` re-evaluates the LIVE size on every step, so elements appended during
///   iteration are visited by the same loop (interpreter-locked live-iteration contract);
/// - `get` re-checks the live size for bounds and resolves the element through
///   `operator_index_const` on each access, so reallocation caused by mutation can never leave a
///   dangling cached base pointer behind;
/// - `next` only copies the holder + increments the index (it must NOT reuse any snapshot-based
///   copy that would pin the iteration to a detached array).
///
/// Init contract: helpers here call `gdcc_packed_<slug>_internal_ptr`, so the translation unit
/// must have run `gdcc_packed_ref_init()` (generated entry modules wire it into `initialize()`).

#define GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY( \
    Slug, \
    TypeName, \
    OpIndexConst, \
    GetReturnType, \
    GetValueExpr, \
    GetOobExpr \
) \
typedef struct gdcc_for_packed_##Slug##_iter { \
    godot_Variant source; \
    godot_int index; \
} gdcc_for_packed_##Slug##_iter; \
\
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_init(void) { \
    /* A nil source is never iterated: `from` always produces the real loop state. Destroy of a \
     * nil Variant is a no-op, so the zero-iteration path stays safe. */ \
    return (gdcc_for_packed_##Slug##_iter){ \
        .source = godot_new_Variant_nil(), \
        .index = 0, \
    }; \
} \
\
static inline void gdcc_for_packed_##Slug##_iter_destroy(gdcc_for_packed_##Slug##_iter *state) { \
    gdcc_packed_ref_destroy(&state->source); \
    state->index = 0; \
} \
\
/* Holder copy shares the underlying array identity; iteration position is per-state. */ \
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_copy( \
    const gdcc_for_packed_##Slug##_iter *src \
) { \
    return (gdcc_for_packed_##Slug##_iter){ \
        .source = gdcc_packed_ref_copy(&src->source), \
        .index = src->index, \
    }; \
} \
\
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_from( \
    const godot_Variant *source \
) { \
    return (gdcc_for_packed_##Slug##_iter){ \
        .source = gdcc_packed_ref_copy(source), \
        .index = 0, \
    }; \
} \
\
static inline godot_bool gdcc_for_packed_##Slug##_iter_should_continue( \
    const gdcc_for_packed_##Slug##_iter *state \
) { \
    /* Live size on every step: elements appended during iteration ARE visited. */ \
    godot_int live_size = godot_Packed##TypeName##_size( \
            gdcc_packed_##Slug##_internal_ptr(&state->source)); \
    return state->index < live_size; \
} \
\
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_next( \
    const gdcc_for_packed_##Slug##_iter *state \
) { \
    /* Holder copy + index increment only; no snapshot/pointer refresh of any kind. */ \
    gdcc_for_packed_##Slug##_iter next_state = gdcc_for_packed_##Slug##_iter_copy(state); \
    next_state.index = state->index + 1; \
    return next_state; \
} \
\
static inline GetReturnType gdcc_for_packed_##Slug##_iter_get( \
    const gdcc_for_packed_##Slug##_iter *state \
) { \
    /* Re-resolve per access: mutation may realloc the backing buffer, and shrink below the \
     * current index is clamped by the live-size bounds check (matching `should_continue`). */ \
    godot_Packed##TypeName *live = gdcc_packed_##Slug##_internal_ptr(&state->source); \
    if (unlikely(state->index < 0 || state->index >= godot_Packed##TypeName##_size(live))) { \
        return GetOobExpr; \
    } \
    /* Typed by each family's OpIndexConst return; GetValueExpr consumes this pointer. \
     * NOTE: keep macro-body comments as block comments — `//` would swallow the macro body. */ \
    const void *gdcc_elem_ptr = OpIndexConst(live, state->index); \
    return GetValueExpr; \
}

GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    byte_array,
    ByteArray,
    godot_packed_byte_array_operator_index_const,
    godot_int,
    (godot_int)(*(const uint8_t *)gdcc_elem_ptr),
    0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    int32_array,
    Int32Array,
    godot_packed_int32_array_operator_index_const,
    godot_int,
    (godot_int)(*(const int32_t *)gdcc_elem_ptr),
    0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    int64_array,
    Int64Array,
    godot_packed_int64_array_operator_index_const,
    godot_int,
    (godot_int)(*(const int64_t *)gdcc_elem_ptr),
    0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    float32_array,
    Float32Array,
    godot_packed_float32_array_operator_index_const,
    godot_float,
    (godot_float)(*(const float *)gdcc_elem_ptr),
    (godot_float)0.0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    float64_array,
    Float64Array,
    godot_packed_float64_array_operator_index_const,
    godot_float,
    (godot_float)(*(const double *)gdcc_elem_ptr),
    (godot_float)0.0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    string_array,
    StringArray,
    godot_packed_string_array_operator_index_const,
    godot_String,
    godot_new_String_with_String((const godot_String *)gdcc_elem_ptr),
    godot_new_String()
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    vector2_array,
    Vector2Array,
    godot_packed_vector2_array_operator_index_const,
    godot_Vector2,
    *(const godot_Vector2 *)gdcc_elem_ptr,
    ((godot_Vector2){0})
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    vector3_array,
    Vector3Array,
    godot_packed_vector3_array_operator_index_const,
    godot_Vector3,
    *(const godot_Vector3 *)gdcc_elem_ptr,
    ((godot_Vector3){0})
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    vector4_array,
    Vector4Array,
    godot_packed_vector4_array_operator_index_const,
    godot_Vector4,
    *(const godot_Vector4 *)gdcc_elem_ptr,
    ((godot_Vector4){0})
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    color_array,
    ColorArray,
    godot_packed_color_array_operator_index_const,
    godot_Color,
    *(const godot_Color *)gdcc_elem_ptr,
    ((godot_Color){0})
)

#undef GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY

#endif
