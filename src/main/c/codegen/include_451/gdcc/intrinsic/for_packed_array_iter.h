#ifndef GDCC_INTRINSIC_FOR_PACKED_ARRAY_ITER_H
#define GDCC_INTRINSIC_FOR_PACKED_ARRAY_ITER_H

#include <godot_binding.h>
#include <string.h>

/// Discriminator for the owned Packed*Array snapshot stored in the union.
typedef enum gdcc_for_packed_array_kind {
    GDCC_FOR_PACKED_ARRAY_KIND_NONE = 0,
    GDCC_FOR_PACKED_ARRAY_KIND_BYTE,
    GDCC_FOR_PACKED_ARRAY_KIND_INT32,
    GDCC_FOR_PACKED_ARRAY_KIND_INT64,
    GDCC_FOR_PACKED_ARRAY_KIND_FLOAT32,
    GDCC_FOR_PACKED_ARRAY_KIND_FLOAT64,
    GDCC_FOR_PACKED_ARRAY_KIND_STRING,
    GDCC_FOR_PACKED_ARRAY_KIND_VECTOR2,
    GDCC_FOR_PACKED_ARRAY_KIND_VECTOR3,
    GDCC_FOR_PACKED_ARRAY_KIND_VECTOR4,
    GDCC_FOR_PACKED_ARRAY_KIND_COLOR,
} gdcc_for_packed_array_kind;

/// Specialized Packed*Array iterator (godot-cpp Iterator style).
/// - Owns a typed Packed*Array snapshot (COW shared).
/// - Caches element base pointer from `operator_index_const(..., 0)` once at init.
/// - `get` reads via pointer arithmetic and builds an owned Variant element (no
///   per-element `variant_get_indexed` / temporary typed unpack).
typedef struct gdcc_for_packed_array_iter {
    gdcc_for_packed_array_kind kind;
    union {
        godot_PackedByteArray byte_array;
        godot_PackedInt32Array int32_array;
        godot_PackedInt64Array int64_array;
        godot_PackedFloat32Array float32_array;
        godot_PackedFloat64Array float64_array;
        godot_PackedStringArray string_array;
        godot_PackedVector2Array vector2_array;
        godot_PackedVector3Array vector3_array;
        godot_PackedVector4Array vector4_array;
        godot_PackedColorArray color_array;
    } source;
    const void *ptr;
    godot_int index;
    godot_int size;
} gdcc_for_packed_array_iter;

static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_init(void) {
    gdcc_for_packed_array_iter state;
    memset(&state, 0, sizeof(state));
    state.kind = GDCC_FOR_PACKED_ARRAY_KIND_NONE;
    return state;
}

static inline void gdcc_for_packed_array_iter_destroy(gdcc_for_packed_array_iter *state) {
    switch (state->kind) {
        case GDCC_FOR_PACKED_ARRAY_KIND_BYTE:
            godot_PackedByteArray_destroy(&state->source.byte_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_INT32:
            godot_PackedInt32Array_destroy(&state->source.int32_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_INT64:
            godot_PackedInt64Array_destroy(&state->source.int64_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_FLOAT32:
            godot_PackedFloat32Array_destroy(&state->source.float32_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_FLOAT64:
            godot_PackedFloat64Array_destroy(&state->source.float64_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_STRING:
            godot_PackedStringArray_destroy(&state->source.string_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR2:
            godot_PackedVector2Array_destroy(&state->source.vector2_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR3:
            godot_PackedVector3Array_destroy(&state->source.vector3_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR4:
            godot_PackedVector4Array_destroy(&state->source.vector4_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_COLOR:
            godot_PackedColorArray_destroy(&state->source.color_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_NONE:
        default:
            break;
    }
    state->kind = GDCC_FOR_PACKED_ARRAY_KIND_NONE;
    state->ptr = NULL;
    state->index = 0;
    state->size = 0;
}

static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_copy(
    const gdcc_for_packed_array_iter *src
) {
    gdcc_for_packed_array_iter dest;
    memset(&dest, 0, sizeof(dest));
    dest.kind = src->kind;
    dest.ptr = src->ptr;
    dest.index = src->index;
    dest.size = src->size;
    switch (src->kind) {
        case GDCC_FOR_PACKED_ARRAY_KIND_BYTE:
            dest.source.byte_array =
                godot_new_PackedByteArray_with_PackedByteArray(&src->source.byte_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_INT32:
            dest.source.int32_array =
                godot_new_PackedInt32Array_with_PackedInt32Array(&src->source.int32_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_INT64:
            dest.source.int64_array =
                godot_new_PackedInt64Array_with_PackedInt64Array(&src->source.int64_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_FLOAT32:
            dest.source.float32_array =
                godot_new_PackedFloat32Array_with_PackedFloat32Array(&src->source.float32_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_FLOAT64:
            dest.source.float64_array =
                godot_new_PackedFloat64Array_with_PackedFloat64Array(&src->source.float64_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_STRING:
            dest.source.string_array =
                godot_new_PackedStringArray_with_PackedStringArray(&src->source.string_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR2:
            dest.source.vector2_array =
                godot_new_PackedVector2Array_with_PackedVector2Array(&src->source.vector2_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR3:
            dest.source.vector3_array =
                godot_new_PackedVector3Array_with_PackedVector3Array(&src->source.vector3_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR4:
            dest.source.vector4_array =
                godot_new_PackedVector4Array_with_PackedVector4Array(&src->source.vector4_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_COLOR:
            dest.source.color_array =
                godot_new_PackedColorArray_with_PackedColorArray(&src->source.color_array);
            break;
        case GDCC_FOR_PACKED_ARRAY_KIND_NONE:
        default:
            break;
    }
    return dest;
}

#define GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(KindEnum, TypeName, Field, OpIndexConst) \
static inline gdcc_for_packed_array_iter gdcc_for_packed_##TypeName##_iter_from( \
    const godot_Packed##TypeName *source \
) { \
    godot_Packed##TypeName owned = \
        godot_new_Packed##TypeName##_with_Packed##TypeName(source); \
    godot_int size = godot_Packed##TypeName##_size(&owned); \
    const void *ptr = NULL; \
    if (size > 0) { \
        ptr = (const void *)OpIndexConst(&owned, 0); \
    } \
    gdcc_for_packed_array_iter state; \
    memset(&state, 0, sizeof(state)); \
    state.kind = KindEnum; \
    state.source.Field = owned; \
    state.ptr = ptr; \
    state.index = 0; \
    state.size = size; \
    return state; \
}

GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_BYTE, ByteArray, byte_array,
    godot_packed_byte_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_INT32, Int32Array, int32_array,
    godot_packed_int32_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_INT64, Int64Array, int64_array,
    godot_packed_int64_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_FLOAT32, Float32Array, float32_array,
    godot_packed_float32_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_FLOAT64, Float64Array, float64_array,
    godot_packed_float64_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_STRING, StringArray, string_array,
    godot_packed_string_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_VECTOR2, Vector2Array, vector2_array,
    godot_packed_vector2_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_VECTOR3, Vector3Array, vector3_array,
    godot_packed_vector3_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_VECTOR4, Vector4Array, vector4_array,
    godot_packed_vector4_array_operator_index_const)
GDCC_DEFINE_PACKED_ARRAY_ITER_FROM(
    GDCC_FOR_PACKED_ARRAY_KIND_COLOR, ColorArray, color_array,
    godot_packed_color_array_operator_index_const)

#undef GDCC_DEFINE_PACKED_ARRAY_ITER_FROM

static inline godot_bool gdcc_for_packed_array_iter_should_continue(
    const gdcc_for_packed_array_iter *state
) {
    return state->index < state->size;
}

static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_next(
    const gdcc_for_packed_array_iter *state
) {
    gdcc_for_packed_array_iter next_state = gdcc_for_packed_array_iter_copy(state);
    next_state.index = state->index + 1;
    return next_state;
}

/// Materializes the current element into an owned Variant by reading the cached base pointer.
static inline godot_Variant gdcc_for_packed_array_iter_get(const gdcc_for_packed_array_iter *state) {
    if (state->ptr == NULL || state->index < 0 || state->index >= state->size) {
        return godot_new_Variant_nil();
    }
    switch (state->kind) {
        case GDCC_FOR_PACKED_ARRAY_KIND_BYTE: {
            uint8_t value = ((const uint8_t *)state->ptr)[state->index];
            return godot_new_Variant_with_int((godot_int)value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_INT32: {
            int32_t value = ((const int32_t *)state->ptr)[state->index];
            return godot_new_Variant_with_int((godot_int)value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_INT64: {
            int64_t value = ((const int64_t *)state->ptr)[state->index];
            return godot_new_Variant_with_int((godot_int)value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_FLOAT32: {
            float value = ((const float *)state->ptr)[state->index];
            return godot_new_Variant_with_float((godot_float)value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_FLOAT64: {
            double value = ((const double *)state->ptr)[state->index];
            return godot_new_Variant_with_float((godot_float)value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_STRING: {
            const godot_String *value = &((const godot_String *)state->ptr)[state->index];
            return godot_new_Variant_with_String(value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR2: {
            const godot_Vector2 *value = &((const godot_Vector2 *)state->ptr)[state->index];
            return godot_new_Variant_with_Vector2(value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR3: {
            const godot_Vector3 *value = &((const godot_Vector3 *)state->ptr)[state->index];
            return godot_new_Variant_with_Vector3(value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_VECTOR4: {
            const godot_Vector4 *value = &((const godot_Vector4 *)state->ptr)[state->index];
            return godot_new_Variant_with_Vector4(value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_COLOR: {
            const godot_Color *value = &((const godot_Color *)state->ptr)[state->index];
            return godot_new_Variant_with_Color(value);
        }
        case GDCC_FOR_PACKED_ARRAY_KIND_NONE:
        default:
            return godot_new_Variant_nil();
    }
}

#endif
