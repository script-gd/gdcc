#ifndef GDCC_INTRINSIC_FOR_PACKED_ARRAY_ITER_H
#define GDCC_INTRINSIC_FOR_PACKED_ARRAY_ITER_H

#include <godot_binding.h>

/// Per-family Packed*Array for-in iterator helpers.
///
/// Each Packed*Array has its own state struct and typed helpers so `get`/`copy`/`destroy`
/// need no runtime kind switch. The state owns a COW snapshot plus a typed element base
/// pointer cached once at init; `next`/`copy` only bump the COW handle.

#define GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY( \
    Slug, \
    TypeName, \
    ElementCType, \
    OpIndexConst, \
    GetReturnType, \
    GetValueExpr, \
    GetOobExpr \
) \
typedef struct gdcc_for_packed_##Slug##_iter { \
    godot_Packed##TypeName source; \
    const ElementCType *ptr; \
    godot_int index; \
    godot_int size; \
} gdcc_for_packed_##Slug##_iter; \
\
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_init(void) { \
    return (gdcc_for_packed_##Slug##_iter){ \
        .source = godot_new_Packed##TypeName(), \
        .ptr = NULL, \
        .index = 0, \
        .size = 0, \
    }; \
} \
\
static inline void gdcc_for_packed_##Slug##_iter_destroy(gdcc_for_packed_##Slug##_iter *state) { \
    godot_Packed##TypeName##_destroy(&state->source); \
    state->ptr = NULL; \
    state->index = 0; \
    state->size = 0; \
} \
\
/* COW copy shares the same data buffer and holds a refcount on it, so reusing src->ptr
 * remains valid after the previous state is destroyed. */ \
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_copy( \
    const gdcc_for_packed_##Slug##_iter *src \
) { \
    return (gdcc_for_packed_##Slug##_iter){ \
        .source = godot_new_Packed##TypeName##_with_Packed##TypeName(&src->source), \
        .ptr = src->ptr, \
        .index = src->index, \
        .size = src->size, \
    }; \
} \
\
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_from( \
    const godot_Packed##TypeName *source \
) { \
    godot_Packed##TypeName owned = godot_new_Packed##TypeName##_with_Packed##TypeName(source); \
    godot_int size = godot_Packed##TypeName##_size(&owned); \
    const ElementCType *ptr = NULL; \
    if (size > 0) { \
        /* Snapshot is owned by this state and never resized; cached base stays valid. */ \
        ptr = (const ElementCType *)OpIndexConst(&owned, 0); \
    } \
    return (gdcc_for_packed_##Slug##_iter){ \
        .source = owned, \
        .ptr = ptr, \
        .index = 0, \
        .size = size, \
    }; \
} \
\
static inline godot_bool gdcc_for_packed_##Slug##_iter_should_continue( \
    const gdcc_for_packed_##Slug##_iter *state \
) { \
    return state->index < state->size; \
} \
\
static inline gdcc_for_packed_##Slug##_iter gdcc_for_packed_##Slug##_iter_next( \
    const gdcc_for_packed_##Slug##_iter *state \
) { \
    gdcc_for_packed_##Slug##_iter next_state = gdcc_for_packed_##Slug##_iter_copy(state); \
    next_state.index = state->index + 1; \
    return next_state; \
} \
\
static inline GetReturnType gdcc_for_packed_##Slug##_iter_get( \
    const gdcc_for_packed_##Slug##_iter *state \
) { \
    if (state->ptr == NULL || state->index < 0 || state->index >= state->size) { \
        return GetOobExpr; \
    } \
    return GetValueExpr; \
}

GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    byte_array,
    ByteArray,
    uint8_t,
    godot_packed_byte_array_operator_index_const,
    godot_int,
    (godot_int)state->ptr[state->index],
    0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    int32_array,
    Int32Array,
    int32_t,
    godot_packed_int32_array_operator_index_const,
    godot_int,
    (godot_int)state->ptr[state->index],
    0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    int64_array,
    Int64Array,
    int64_t,
    godot_packed_int64_array_operator_index_const,
    godot_int,
    (godot_int)state->ptr[state->index],
    0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    float32_array,
    Float32Array,
    float,
    godot_packed_float32_array_operator_index_const,
    godot_float,
    (godot_float)state->ptr[state->index],
    (godot_float)0.0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    float64_array,
    Float64Array,
    double,
    godot_packed_float64_array_operator_index_const,
    godot_float,
    (godot_float)state->ptr[state->index],
    (godot_float)0.0
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    string_array,
    StringArray,
    godot_String,
    godot_packed_string_array_operator_index_const,
    godot_String,
    godot_new_String_with_String(&state->ptr[state->index]),
    godot_new_String()
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    vector2_array,
    Vector2Array,
    godot_Vector2,
    godot_packed_vector2_array_operator_index_const,
    godot_Vector2,
    state->ptr[state->index],
    ((godot_Vector2){0})
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    vector3_array,
    Vector3Array,
    godot_Vector3,
    godot_packed_vector3_array_operator_index_const,
    godot_Vector3,
    state->ptr[state->index],
    ((godot_Vector3){0})
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    vector4_array,
    Vector4Array,
    godot_Vector4,
    godot_packed_vector4_array_operator_index_const,
    godot_Vector4,
    state->ptr[state->index],
    ((godot_Vector4){0})
)
GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY(
    color_array,
    ColorArray,
    godot_Color,
    godot_packed_color_array_operator_index_const,
    godot_Color,
    state->ptr[state->index],
    ((godot_Color){0})
)

#undef GDCC_DEFINE_PACKED_ARRAY_ITER_FAMILY

#endif
