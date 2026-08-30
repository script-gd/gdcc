#ifndef GDSCRIPT_BUILTINS_H
#define GDSCRIPT_BUILTINS_H

#include <godot_binding.h>
#include <stdio.h>

/// GDScript language-level builtins.
///
/// These helpers back GDScript's own language functions/statements (`assert`, `len`, ...), which
/// are registered by the GDScript module rather than the GDExtension API, so no generated
/// `godot_*` wrapper exists for them. All entry points use the `gdcc_` prefix and the
/// "print runtime error + return type default value" failure style shared with the rest of the
/// gdcc runtime.
///
/// Include contract: this header is pulled in by `gdcc_helper.h` right after the
/// `GDCC_PRINT_RUNTIME_ERROR` macro definition (the helpers below depend on it). It must not
/// include `gdcc_helper.h` itself, otherwise the include graph would cycle.

/// Reports a failed user-level `assert` through the shared runtime-error channel.
///
/// `message_or_null` is the optional user message (a `NULL` pointer means the call had no
/// message argument). The caller emits the default-return edge immediately after this call, so
/// this helper only reports and never alters control flow.
static inline void gdcc_assert_failed(
    const godot_String *message_or_null,
    const char *func,
    const char *file,
    int line
) {
    if (message_or_null == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("Assertion failed.", func, file, line);
        return;
    }
    // godot_string_to_utf8_chars returns the FULL UTF-8 byte length of the string, not the
    // number of bytes actually written (it copies at most max_write_length bytes and never
    // NUL-terminates). Clamp to the buffer capacity before terminating.
    char message_utf8[448];
    GDExtensionInt written = godot_string_to_utf8_chars(message_or_null, message_utf8, sizeof(message_utf8) - 1);
    if (written < 0) {
        written = 0;
    }
    if (written > (GDExtensionInt)sizeof(message_utf8) - 1) {
        written = (GDExtensionInt)sizeof(message_utf8) - 1;
    }
    message_utf8[written] = '\0';
    char desc[512];
    snprintf(desc, sizeof(desc), "Assertion failed: %s", message_utf8);
    GDCC_PRINT_RUNTIME_ERROR(desc, func, file, line);
}

/// `len(value)` per-type entry points — Godot 4.5 `GDScriptUtilityFunctions` semantics:
/// character count for String/StringName, element count for Array/Dictionary and every
/// Packed*Array.
///
/// Each helper takes the already-unpacked concrete payload instead of a Variant. This keeps the
/// measurement logic usable from the intrinsic channel: when the argument type is known at
/// compile time, the backend can statically dispatch to the matching `gdcc_len_*` helper and skip
/// both the Variant type check and the temporary unpack performed by `gdcc_len` below.
static inline godot_int gdcc_len_string(const godot_String *value) {
    return godot_String_length(value);
}

static inline godot_int gdcc_len_string_name(const godot_StringName *value) {
    return godot_StringName_length(value);
}

static inline godot_int gdcc_len_array(const godot_Array *value) {
    return godot_Array_size(value);
}

static inline godot_int gdcc_len_dictionary(const godot_Dictionary *value) {
    return godot_Dictionary_size(value);
}

// All ten Packed*Array helpers share one shape: forward to the generated size binding.
#define GDCC_LEN_PACKED_HELPER(TYPE_NAME, FUNC_SUFFIX)                                    \
    static inline godot_int gdcc_len_##FUNC_SUFFIX(const godot_##TYPE_NAME *value) {      \
        return godot_##TYPE_NAME##_size(value);                                           \
    }
GDCC_LEN_PACKED_HELPER(PackedByteArray, packed_byte_array)
GDCC_LEN_PACKED_HELPER(PackedInt32Array, packed_int32_array)
GDCC_LEN_PACKED_HELPER(PackedInt64Array, packed_int64_array)
GDCC_LEN_PACKED_HELPER(PackedFloat32Array, packed_float32_array)
GDCC_LEN_PACKED_HELPER(PackedFloat64Array, packed_float64_array)
GDCC_LEN_PACKED_HELPER(PackedStringArray, packed_string_array)
GDCC_LEN_PACKED_HELPER(PackedVector2Array, packed_vector2_array)
GDCC_LEN_PACKED_HELPER(PackedVector3Array, packed_vector3_array)
GDCC_LEN_PACKED_HELPER(PackedColorArray, packed_color_array)
GDCC_LEN_PACKED_HELPER(PackedVector4Array, packed_vector4_array)
#undef GDCC_LEN_PACKED_HELPER

/// `len(value)` dynamic dispatch on a Variant payload: unpacks a temporary copy, forwards to the
/// matching per-type helper, and destroys the copy; the caller's Variant is never consumed.
/// Any other Variant type is a runtime error and yields the type default `0`.
static inline godot_int gdcc_len(const godot_Variant *value) {
    switch (godot_variant_get_type(value)) {
        case GDEXTENSION_VARIANT_TYPE_STRING: {
            godot_String payload = godot_new_String_with_Variant(value);
            godot_int result = gdcc_len_string(&payload);
            godot_String_destroy(&payload);
            return result;
        }
        case GDEXTENSION_VARIANT_TYPE_STRING_NAME: {
            godot_StringName payload = godot_new_StringName_with_Variant(value);
            godot_int result = gdcc_len_string_name(&payload);
            godot_StringName_destroy(&payload);
            return result;
        }
        case GDEXTENSION_VARIANT_TYPE_ARRAY: {
            godot_Array payload = godot_new_Array_with_Variant(value);
            godot_int result = gdcc_len_array(&payload);
            godot_Array_destroy(&payload);
            return result;
        }
        case GDEXTENSION_VARIANT_TYPE_DICTIONARY: {
            godot_Dictionary payload = godot_new_Dictionary_with_Variant(value);
            godot_int result = gdcc_len_dictionary(&payload);
            godot_Dictionary_destroy(&payload);
            return result;
        }
// All ten Packed*Array branches share one shape: unpack, dispatch to helper, destroy, return.
#define GDCC_LEN_PACKED_CASE(VARIANT_TYPE, TYPE_NAME, FUNC_SUFFIX)                        \
        case VARIANT_TYPE: {                                                              \
            godot_##TYPE_NAME payload = godot_new_##TYPE_NAME##_with_Variant(value);      \
            godot_int result = gdcc_len_##FUNC_SUFFIX(&payload);                          \
            godot_##TYPE_NAME##_destroy(&payload);                                        \
            return result;                                                                \
        }
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY, PackedByteArray, packed_byte_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY, PackedInt32Array, packed_int32_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY, PackedInt64Array, packed_int64_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY, PackedFloat32Array, packed_float32_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY, PackedFloat64Array, packed_float64_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY, PackedStringArray, packed_string_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY, PackedVector2Array, packed_vector2_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY, PackedVector3Array, packed_vector3_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY, PackedColorArray, packed_color_array)
        GDCC_LEN_PACKED_CASE(GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY, PackedVector4Array, packed_vector4_array)
#undef GDCC_LEN_PACKED_CASE
        default:
            GDCC_PRINT_RUNTIME_ERROR(
                    "len(): unsupported Variant type (expected String, StringName, Array, "
                    "Dictionary, or a Packed*Array).", __func__, __FILE__, __LINE__);
            return 0;
    }
}

/// `char(code)` — Godot 4.5 semantics: only `code < 0 || code > UINT32_MAX` is an error;
/// `code == 0` yields the NUL character, and surrogates or values above U+10FFFF are delegated
/// to `String.chr` (which substitutes U+FFFD) without an additional error, matching the engine.
static inline godot_String gdcc_char(godot_int code) {
    if (code < 0 || code > 4294967295LL) {
        GDCC_PRINT_RUNTIME_ERROR(
                "char(): code point out of range (valid: 0 to 4294967295).",
                __func__, __FILE__, __LINE__);
        return godot_new_String();
    }
    return godot_String_chr(code);
}

/// `ord(value)` — Godot 4.5 semantics: returns the Unicode code point of the first character;
/// the string must be exactly one character long, otherwise a runtime error yields `0`.
static inline godot_int gdcc_ord(const godot_String *value) {
    if (godot_String_length(value) != 1) {
        GDCC_PRINT_RUNTIME_ERROR(
                "ord(): expected a string of exactly 1 character.", __func__, __FILE__, __LINE__);
        return 0;
    }
    return godot_String_unicode_at(value, 0);
}

#endif //GDSCRIPT_BUILTINS_H
