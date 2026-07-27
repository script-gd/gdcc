#ifndef GDCC_INTRINSIC_H
#define GDCC_INTRINSIC_H

#include <godot_binding.h>
#include "gdcc_likely.h"

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

typedef struct gdcc_for_variant_iter {
    godot_Variant source;
    godot_Variant iter;
    godot_bool valid;
    godot_bool has_element;
} gdcc_for_variant_iter;

static inline gdcc_for_variant_iter gdcc_for_variant_iter_init(void) {
    return (gdcc_for_variant_iter){
        .source = godot_new_Variant_nil(),
        .iter = godot_new_Variant_nil(),
        .valid = false,
        .has_element = false,
    };
}

static inline void gdcc_for_variant_iter_destroy(gdcc_for_variant_iter *state) {
    godot_Variant_destroy(&state->source);
    godot_Variant_destroy(&state->iter);
}

static inline gdcc_for_variant_iter gdcc_for_variant_iter_copy(const gdcc_for_variant_iter *src) {
    gdcc_for_variant_iter dest;
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&dest.source,
                           (GDExtensionConstVariantPtr)&src->source);
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&dest.iter,
                           (GDExtensionConstVariantPtr)&src->iter);
    dest.valid = src->valid;
    dest.has_element = src->has_element;
    return dest;
}

/// Initializes generic Variant iteration state from the source iterable.
/// Calls `variant_iter_init` through the GDExtension API; non-iterable values print a runtime
/// error and produce an empty iteration (has_element = false), matching Godot VM behavior.
static inline gdcc_for_variant_iter gdcc_for_variant_iter_from_variant(const godot_Variant *source) {
    gdcc_for_variant_iter state;
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&state.source,
                           (GDExtensionConstVariantPtr)source);
    GDExtensionBool valid = false;
    GDExtensionBool has_first = godot_variant_iter_init(
        (GDExtensionConstVariantPtr)&state.source,
        (GDExtensionUninitializedVariantPtr)&state.iter,
        &valid
    );
    state.valid = valid;
    state.has_element = valid && has_first;
    if (!valid) {
        godot_print_error(
            "Can't iterate on a non-iterable Variant value.",
            "gdcc_for_variant_iter_from_variant",
            __FILE__, __LINE__, true
        );
    }
    return state;
}

static inline godot_bool gdcc_for_variant_iter_should_continue(const gdcc_for_variant_iter *state) {
    return state->valid && state->has_element;
}

/// Advances the iterator by copying state and calling `variant_iter_next` on the copy.
/// Returns a new state value; the input is not modified.
static inline gdcc_for_variant_iter gdcc_for_variant_iter_next(const gdcc_for_variant_iter *state) {
    gdcc_for_variant_iter next_state;
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&next_state.source,
                           (GDExtensionConstVariantPtr)&state->source);
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&next_state.iter,
                           (GDExtensionConstVariantPtr)&state->iter);
    GDExtensionBool valid = false;
    GDExtensionBool has_next = godot_variant_iter_next(
        (GDExtensionConstVariantPtr)&next_state.source,
        (GDExtensionVariantPtr)&next_state.iter,
        &valid
    );
    next_state.valid = valid;
    next_state.has_element = valid && has_next;
    if (!valid) {
        godot_print_error(
            "Variant iteration next failed (iterator became invalid).",
            "gdcc_for_variant_iter_next",
            __FILE__, __LINE__, true
        );
    }
    return next_state;
}

/// Retrieves the current element via `variant_iter_get`. Returns a nil Variant on error.
static inline godot_Variant gdcc_for_variant_iter_get(const gdcc_for_variant_iter *state) {
    godot_Variant result;
    GDExtensionBool valid = false;
    godot_variant_iter_get(
        (GDExtensionConstVariantPtr)&state->source,
        (GDExtensionVariantPtr)&state->iter,
        (GDExtensionUninitializedVariantPtr)&result,
        &valid
    );
    if (!valid) {
        godot_print_error(
            "Variant iteration get failed (iterator became invalid).",
            "gdcc_for_variant_iter_get",
            __FILE__, __LINE__, true
        );
        return godot_new_Variant_nil();
    }
    return result;
}

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

/// Advances the iterator by one position. The source Array is shared via COW (O(1) refcount).
static inline gdcc_for_array_iter gdcc_for_array_iter_next(const gdcc_for_array_iter *state) {
    return (gdcc_for_array_iter){
        .source = godot_new_Array_with_Array(&state->source),
        .index = state->index + 1,
        .size = state->size,
    };
}

static inline godot_Variant gdcc_for_array_iter_get(const gdcc_for_array_iter *state) {
    return godot_Array_get(&state->source, state->index);
}

typedef struct gdcc_for_dictionary_iter {
    godot_Array keys;
    godot_int index;
    godot_int size;
} gdcc_for_dictionary_iter;

static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_init(void) {
    return (gdcc_for_dictionary_iter){
        .keys = godot_new_Array(),
        .index = 0,
        .size = 0,
    };
}

static inline void gdcc_for_dictionary_iter_destroy(gdcc_for_dictionary_iter *state) {
    godot_Array_destroy(&state->keys);
}

static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_copy(const gdcc_for_dictionary_iter *src) {
    return (gdcc_for_dictionary_iter){
        .keys = godot_new_Array_with_Array(&src->keys),
        .index = src->index,
        .size = src->size,
    };
}

/// Initializes dictionary key iteration by extracting all keys into an internal Array.
/// Godot Dictionary iteration yields keys; this snapshot approach avoids cursor invalidation
/// if the dictionary is mutated during iteration. Note: Godot VM uses a live cursor that
/// invalidates on mutation; this snapshot is a deliberate safer divergence.
static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_from_dictionary(const godot_Dictionary *source) {
    godot_Array keys = godot_Dictionary_keys(source);
    godot_int size = godot_Array_size(&keys);
    return (gdcc_for_dictionary_iter){
        .keys = keys,
        .index = 0,
        .size = size,
    };
}

static inline godot_bool gdcc_for_dictionary_iter_should_continue(const gdcc_for_dictionary_iter *state) {
    return state->index < state->size;
}

/// Advances the iterator by one position. The keys Array is shared via COW (O(1) refcount).
static inline gdcc_for_dictionary_iter gdcc_for_dictionary_iter_next(const gdcc_for_dictionary_iter *state) {
    return (gdcc_for_dictionary_iter){
        .keys = godot_new_Array_with_Array(&state->keys),
        .index = state->index + 1,
        .size = state->size,
    };
}

static inline godot_Variant gdcc_for_dictionary_iter_get(const gdcc_for_dictionary_iter *state) {
    return godot_Array_get(&state->keys, state->index);
}

typedef struct gdcc_for_packed_array_iter {
    godot_Variant source;
    godot_int index;
    godot_int size;
} gdcc_for_packed_array_iter;

static inline godot_int gdcc_for_packed_array_iter_size_from_variant(const godot_Variant *source) {
    switch (godot_variant_get_type((GDExtensionConstVariantPtr)source)) {
        case GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY: {
            godot_PackedByteArray array = godot_new_PackedByteArray_with_Variant(source);
            godot_int size = godot_PackedByteArray_size(&array);
            godot_PackedByteArray_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY: {
            godot_PackedInt32Array array = godot_new_PackedInt32Array_with_Variant(source);
            godot_int size = godot_PackedInt32Array_size(&array);
            godot_PackedInt32Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY: {
            godot_PackedInt64Array array = godot_new_PackedInt64Array_with_Variant(source);
            godot_int size = godot_PackedInt64Array_size(&array);
            godot_PackedInt64Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY: {
            godot_PackedFloat32Array array = godot_new_PackedFloat32Array_with_Variant(source);
            godot_int size = godot_PackedFloat32Array_size(&array);
            godot_PackedFloat32Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY: {
            godot_PackedFloat64Array array = godot_new_PackedFloat64Array_with_Variant(source);
            godot_int size = godot_PackedFloat64Array_size(&array);
            godot_PackedFloat64Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY: {
            godot_PackedStringArray array = godot_new_PackedStringArray_with_Variant(source);
            godot_int size = godot_PackedStringArray_size(&array);
            godot_PackedStringArray_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY: {
            godot_PackedVector2Array array = godot_new_PackedVector2Array_with_Variant(source);
            godot_int size = godot_PackedVector2Array_size(&array);
            godot_PackedVector2Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY: {
            godot_PackedVector3Array array = godot_new_PackedVector3Array_with_Variant(source);
            godot_int size = godot_PackedVector3Array_size(&array);
            godot_PackedVector3Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY: {
            godot_PackedVector4Array array = godot_new_PackedVector4Array_with_Variant(source);
            godot_int size = godot_PackedVector4Array_size(&array);
            godot_PackedVector4Array_destroy(&array);
            return size;
        }
        case GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY: {
            godot_PackedColorArray array = godot_new_PackedColorArray_with_Variant(source);
            godot_int size = godot_PackedColorArray_size(&array);
            godot_PackedColorArray_destroy(&array);
            return size;
        }
        default:
            return 0;
    }
}

static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_init(void) {
    return (gdcc_for_packed_array_iter){
        .source = godot_new_Variant_nil(),
        .index = 0,
        .size = 0,
    };
}

static inline void gdcc_for_packed_array_iter_destroy(gdcc_for_packed_array_iter *state) {
    godot_Variant_destroy(&state->source);
}

static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_copy(const gdcc_for_packed_array_iter *src) {
    gdcc_for_packed_array_iter dest;
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&dest.source,
                           (GDExtensionConstVariantPtr)&src->source);
    dest.index = src->index;
    dest.size = src->size;
    return dest;
}

/// Snapshots the packed array as a Variant and caches size so later index access matches
/// Godot's Packed*Array `Variant::iter_*` protocol (int index state, element via get).
static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_from_packed_array(
    const godot_Variant *source
) {
    gdcc_for_packed_array_iter state;
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&state.source,
                           (GDExtensionConstVariantPtr)source);
    state.index = 0;
    state.size = gdcc_for_packed_array_iter_size_from_variant(&state.source);
    return state;
}

static inline godot_bool gdcc_for_packed_array_iter_should_continue(const gdcc_for_packed_array_iter *state) {
    return state->index < state->size;
}

static inline gdcc_for_packed_array_iter gdcc_for_packed_array_iter_next(
    const gdcc_for_packed_array_iter *state
) {
    gdcc_for_packed_array_iter next_state;
    godot_variant_new_copy((GDExtensionUninitializedVariantPtr)&next_state.source,
                           (GDExtensionConstVariantPtr)&state->source);
    next_state.index = state->index + 1;
    next_state.size = state->size;
    return next_state;
}

static inline godot_Variant gdcc_for_packed_array_iter_get(const gdcc_for_packed_array_iter *state) {
    godot_Variant result;
    GDExtensionBool valid = false;
    GDExtensionBool oob = false;
    godot_variant_get_indexed(
        (GDExtensionConstVariantPtr)&state->source,
        state->index,
        (GDExtensionUninitializedVariantPtr)&result,
        &valid,
        &oob
    );
    if (!valid || oob) {
        return godot_new_Variant_nil();
    }
    return result;
}

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

/// Wrapper-only inbound constructors for call_func arguments whose accepted Variant payload
/// runtime type differs from the published method metadata.
/// The generated wrapper must run its runtime type gate first; these helpers materialize the
/// already-accepted payload and do not set r_error themselves.
static inline godot_StringName gdcc_new_StringName_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_STRING) {
        godot_String source = godot_new_String_with_Variant(value);
        godot_StringName result = godot_new_StringName_with_String(&source);
        godot_String_destroy(&source);
        return result;
    }
    return godot_new_StringName_with_Variant(value);
}

static inline godot_String gdcc_new_String_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_STRING_NAME) {
        godot_StringName source = godot_new_StringName_with_Variant(value);
        godot_String result = godot_new_String_with_StringName(&source);
        godot_StringName_destroy(&source);
        return result;
    }
    return godot_new_String_with_Variant(value);
}

static inline godot_Vector2 gdcc_new_Vector2_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR2I) {
        godot_Vector2i source = godot_new_Vector2i_with_Variant(value);
        return godot_new_Vector2_with_Vector2i(&source);
    }
    return godot_new_Vector2_with_Variant(value);
}

static inline godot_Vector3 gdcc_new_Vector3_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR3I) {
        godot_Vector3i source = godot_new_Vector3i_with_Variant(value);
        return godot_new_Vector3_with_Vector3i(&source);
    }
    return godot_new_Vector3_with_Variant(value);
}

static inline godot_Vector4 gdcc_new_Vector4_from_call_arg_variant(
    GDExtensionVariantPtr value,
    GDExtensionVariantType type
) {
    if (type == GDEXTENSION_VARIANT_TYPE_VECTOR4I) {
        godot_Vector4i source = godot_new_Vector4i_with_Variant(value);
        return godot_new_Vector4_with_Vector4i(&source);
    }
    return godot_new_Vector4_with_Variant(value);
}

#endif
