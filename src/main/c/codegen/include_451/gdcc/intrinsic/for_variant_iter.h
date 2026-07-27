#ifndef GDCC_INTRINSIC_FOR_VARIANT_ITER_H
#define GDCC_INTRINSIC_FOR_VARIANT_ITER_H

#include <godot_binding.h>

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

#endif
