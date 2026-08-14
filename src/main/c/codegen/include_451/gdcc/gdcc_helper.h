#ifndef GDCC_HELPER_H
#define GDCC_HELPER_H

#include <godot_binding.h>
#include <gdcc_builtin_ctor.h>
#include <gdcc_string_name.h>
#include <gdcc_string.h>
#include <gdcc_call.h>
#include <gdcc_callable.h>
#include <gdcc_bind.h>
#include <gdcc_operator.h>
#include <gdcc_intrinsic.h>
#include <stdio.h>
#include <math.h>
#include <stdint.h>

#if !defined(GDE_EXPORT)
#if defined(_WIN32)
#define GDE_EXPORT __declspec(dllexport)
#elif defined(__GNUC__)
#define GDE_EXPORT __attribute__((visibility("default")))
#else
#define GDE_EXPORT
#endif
#endif

#define GDCC_PRINT_RUNTIME_ERROR(desc, function_name, file_name, line_number)                \
do {                                                                                          \
    const char* __gdcc_src_file = (file_name) ? (file_name) : "<unknown>";                   \
    godot_print_error((desc), (function_name), __gdcc_src_file, (line_number), true);        \
} while (0)

/// Object Property Getters

#define GDCC_DEFINE_OBJECT_GETTER(ReturnType, ReturnTypeName)                                 \
static inline ReturnType gdcc_object_get_##ReturnTypeName(                                    \
    GDExtensionConstObjectPtr obj, const godot_StringName* property_name) {                   \
        godot_Variant result = godot_Object_get(obj, property_name);                          \
        ReturnType value = godot_new_##ReturnTypeName##_with_Variant(&result);                \
        godot_variant_destroy(&result);                                                       \
    return value;                                                                             \
}

GDCC_DEFINE_OBJECT_GETTER(godot_float, float)
GDCC_DEFINE_OBJECT_GETTER(godot_bool, bool)
GDCC_DEFINE_OBJECT_GETTER(godot_int, int)
GDCC_DEFINE_OBJECT_GETTER(godot_Vector3, Vector3)

#define GDCC_DEFINE_OBJECT_SETTER(ValueType, ValueTypeName)                                \
static inline void gdcc_object_set_##ValueTypeName(                                        \
    GDExtensionObjectPtr obj, const godot_StringName* property_name, ValueType value) {    \
    godot_Variant v = godot_new_Variant_with_##ValueTypeName(value);                       \
    godot_Object_set(obj, property_name, &v);                                              \
    godot_variant_destroy(&v);                                                             \
}

GDCC_DEFINE_OBJECT_SETTER(godot_float, float)
GDCC_DEFINE_OBJECT_SETTER(godot_bool, bool)
GDCC_DEFINE_OBJECT_SETTER(godot_int, int)
GDCC_DEFINE_OBJECT_SETTER(godot_Vector3*, Vector3)

#define godot_Nil godot_Variant
#define godot_TypedArray(value)  godot_Array
#define godot_TypedDictionary(key, value)  godot_Dictionary

static godot_Engine* _gd_engine;

static void gdcc_init() {
    _gd_engine = godot_Engine_singleton();
}

static bool gdcc_is_editor_hint() {
    return godot_Engine_is_editor_hint(_gd_engine);
}

/// Function attribute macros for side-effect-free query helpers.
/// They degrade to nothing on toolchains without GNU attribute support.
#if defined(__GNUC__)
#define GDCC_PURE __attribute__((pure))
#define GDCC_CONST __attribute__((const))
#else
#define GDCC_PURE
#define GDCC_CONST
#endif

/// Godot 4.5.1 ObjectDB marks RefCounted object IDs with the high bit.
/// GDCC uses it only as the dynamic RefCounted fast-path hint, never as object identity.
#define GDCC_OBJECT_ID_REFERENCE_BIT (UINT64_C(1) << 63)

/// Checks the ObjectID reference bit without touching ObjectDB.
static inline GDCC_CONST godot_bool gdcc_object_id_is_ref_counted(GDObjectInstanceID instance_id) {
    return (instance_id & GDCC_OBJECT_ID_REFERENCE_BIT) != 0;
}

/// Static-YES RefCounted retain. Side-effecting: mutates reference count (not pure/const).
/// `obj` must be the validated live raw pointer (`<T>_fat_ptr_live_object`); never pass a fat struct.
static void own_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_RefCounted* rc = obj;
    godot_RefCounted_reference(rc);
}

/// Runtime RefCounted retain for an object whose static type is unknown.
/// Side-effecting: may mutate reference count (not pure/const).
/// `obj` must be the validated live raw pointer (`<T>_fat_ptr_live_object`); `instance_id` is the
/// fat pointer's cached ID. The ObjectID reference bit (not a ClassDB class-name query) decides
/// RefCounted-ness; the ID is never recovered from `obj`, which may be a freed non-RefCounted raw.
static void try_own_object(const GDExtensionObjectPtr obj, const GDObjectInstanceID instance_id) {
    if (obj == NULL || !gdcc_object_id_is_ref_counted(instance_id)) {
        return;
    }
    godot_RefCounted* rc = obj;
    godot_RefCounted_reference(rc);
}

/// Static-YES RefCounted release. Side-effecting: mutates reference count and may destroy (not pure/const).
/// If `unreference` returns true (refcount reached zero and no veto), the object is destroyed.
/// Same raw-pointer argument contract as `own_object`.
static void release_object(const GDExtensionObjectPtr obj) {
    if (obj == NULL) {
        return;
    }
    godot_RefCounted* rc = obj;
    if (godot_RefCounted_unreference(rc)) {
        godot_object_destroy(obj);
    }
}

/// Runtime RefCounted release for an object whose static type is unknown.
/// Side-effecting: may mutate reference count and destroy (not pure/const).
/// If `unreference` returns true (refcount reached zero and no veto), the object is destroyed.
/// Same pointer/ID contract as `try_own_object`: reference bit decides, ID never recovered from `obj`.
static void try_release_object(const GDExtensionObjectPtr obj, const GDObjectInstanceID instance_id) {
    if (obj == NULL || !gdcc_object_id_is_ref_counted(instance_id)) {
        return;
    }
    godot_RefCounted* rc = obj;
    if (godot_RefCounted_unreference(rc)) {
        godot_object_destroy(obj);
    }
}

/// Destroys an owned object whose static type is unknown.
/// Side-effecting: release RefCounted strong ref (destroying if last) or free manually-managed object.
/// Reference-bit hit: unreference + conditional destroy. Miss: unconditional destroy.
/// `obj` must be the validated live raw pointer; a NULL `obj` (already freed) is a no-op.
static void try_destroy_object(const GDExtensionObjectPtr obj, const GDObjectInstanceID instance_id) {
    if (obj == NULL) {
        return;
    }
    if (gdcc_object_id_is_ref_counted(instance_id)) {
        // Same as try_release_object: never force-destroy a live RefCounted object.
        godot_RefCounted* rc = obj;
        if (godot_RefCounted_unreference(rc)) {
            godot_object_destroy(obj);
        }
    } else {
        godot_object_destroy(obj);
    }
}

/// Resolves a live raw Godot object pointer from an instance ID.
/// ID 0 is canonical null; non-RefCounted IDs must be validated through ObjectDB.
/// PURE is safe because the inner Godot function-pointer call is a conservative global-state
/// barrier, so compilers will not CSE this query across Godot API or lifecycle calls.
static inline GDCC_PURE GDExtensionObjectPtr gdcc_object_live_ptr(GDObjectInstanceID instance_id) {
    if (instance_id == 0) {
        return NULL;
    }
    return godot_object_get_instance_from_id(instance_id);
}

/// Returns whether an instance ID denotes a live object under the ownership invariant.
/// RefCounted reference-bit hits are treated as live without an ObjectDB lookup.
/// After release/destroy the stale RefCounted ID still has the reference bit set and will
/// false-positive here; callers must never query liveness after releasing ownership.
static inline GDCC_PURE godot_bool gdcc_object_is_live(GDObjectInstanceID instance_id) {
    if (instance_id == 0) {
        return false;
    }
    if (gdcc_object_id_is_ref_counted(instance_id)) {
        return true;
    }
    return gdcc_object_live_ptr(instance_id) != NULL;
}

/// Semantic inverse of `gdcc_object_is_live` for an instance ID.
/// Fat-pointer `object_is_null` / `assert_object_live` must pass both cached raw pointer and ID
/// through `gdcc_object_is_null_raw_and_id`; do not recover ID from a possibly-dead raw pointer.
static inline GDCC_PURE godot_bool gdcc_object_is_null(GDObjectInstanceID instance_id) {
    return !gdcc_object_is_live(instance_id);
}

/// Compares two already-normalized raw Godot object pointers for identity.
/// Callers must materialize equality-normalized raws first (null∪freed → NULL; live → Godot raw).
/// This helper does not perform liveness checks or compare instance IDs.
static inline GDCC_CONST godot_bool gdcc_object_live_ptrs_equal(GDExtensionObjectPtr left, GDExtensionObjectPtr right) {
    return left == right;
}

/// Reads an instance ID from a raw object pointer that the caller already guarantees is live.
/// Mapping NULL -> 0 is safe. Calling this on a freed/dangling raw pointer is use-after-free.
/// Only `from_raw` capture paths may use this; null/equality/assert must never recover ID from raw.
static inline GDObjectInstanceID gdcc_object_id_from_raw(GDExtensionObjectPtr raw) {
    if (raw == NULL) {
        return 0;
    }
    return godot_object_get_instance_id(raw);
}

/// Null/freed query for a fat-pointer pair `(raw, instance_id)`.
/// Never calls `godot_object_get_instance_id` on raw. ID is the liveness authority; raw == NULL is
/// also treated as null. Generic and untyped: no per-class specialization.
static inline GDCC_PURE godot_bool gdcc_object_is_null_raw_and_id(
        GDExtensionObjectPtr raw,
        GDObjectInstanceID instance_id) {
    if (raw == NULL || instance_id == 0) {
        return true;
    }
    return gdcc_object_is_null(instance_id);
}

/// Converts a Godot raw object pointer back to the bound GDCC native instance.
/// This helper is representation-only and must not be treated as a retain/release boundary.
static GDExtensionClassInstancePtr gdcc_object_from_godot_object_ptr(GDExtensionObjectPtr ptr) {
    const GDExtensionInstanceBindingCallbacks callbacks = {
        .create_callback = NULL,
        .free_callback = NULL,
        .reference_callback = NULL,
    };
    return godot_object_get_instance_binding(ptr, class_library, &callbacks);
}

/// Preferred conversion entry for GDCC -> Godot object pointers.
/// `object_ptr_helper` must be a generated per-class helper like `MyClass_object_ptr`.
/// This macro is representation-only and must not be treated as a retain/release boundary.
#define gdcc_object_to_godot_object_ptr(obj, object_ptr_helper) ({ __typeof__(obj) _o = (obj); _o ? object_ptr_helper(_o) : NULL; })

/// Unpacks an OBJECT Variant into the bound GDCC native instance.
/// Returns NULL for null or freed object payloads (Godot freed == null semantics).
/// Liveness is validated through ObjectDB before any pointer dereference (ID-first pattern).
static GDExtensionClassInstancePtr godot_new_gdcc_Object_with_Variant(const godot_Variant* value) {
    GDObjectInstanceID id = godot_variant_get_object_instance_id(value);
    const GDExtensionObjectPtr obj = gdcc_object_live_ptr(id);
    if (obj == NULL) {
        return NULL;
    }
    return gdcc_object_from_godot_object_ptr(obj);
}

/// Helper: convert a godot_StringName to a UTF-8 C string into a provided buffer.
/// Returns the number of characters written (excluding null terminator).
static GDExtensionInt gdcc_string_name_to_utf8(const godot_StringName *sn, char *buf, GDExtensionInt buf_size) {
    godot_String str = godot_new_String_with_StringName(sn);
    GDExtensionInt len = godot_string_to_utf8_chars(&str, buf, buf_size - 1);
    buf[len] = '\0';
    godot_String_destroy(&str);
    return len;
}

/// Helper: convert a Variant type enum name to a UTF-8 C string into a provided buffer.
/// Returns the number of characters written (excluding null terminator).
static GDExtensionInt gdcc_variant_type_to_utf8(GDExtensionVariantType type, char *buf, GDExtensionInt buf_size) {
    godot_String str;
    godot_variant_get_type_name(type, &str);
    if (buf == NULL || buf_size <= 0) {
        godot_String_destroy(&str);
        return 0;
    }
    GDExtensionInt len = godot_string_to_utf8_chars(&str, buf, buf_size - 1);
    if (len < 0) {
        len = 0;
    }
    if (len >= buf_size) {
        len = buf_size - 1;
    }
    buf[len] = '\0';
    godot_String_destroy(&str);
    return len;
}

/// Runtime type guard for Variant -> builtin unpack.
/// Requires exact GDExtensionVariantType match.
static godot_bool gdcc_check_variant_type_builtin(const godot_Variant *value,
                                                  GDExtensionVariantType expected_type) {
    if (value == NULL) {
        return false;
    }
    return godot_variant_get_type(value) == expected_type;
}

/// Runtime type guard for Variant -> Object unpack.
/// - exact type match always passes.
/// - subclass match is optional via `allow_subclass`.
/// - null or freed object payload is accepted for object targets (Godot freed == null semantics).
/// Liveness is validated through ObjectDB before any pointer dereference (ID-first pattern).
static godot_bool gdcc_check_variant_type_object(const godot_Variant *value,
                                                 const godot_StringName *expected_class_name,
                                                 godot_bool allow_subclass) {
    if (value == NULL || expected_class_name == NULL) {
        return false;
    }
    if (godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return false;
    }

    GDObjectInstanceID instance_id = godot_variant_get_object_instance_id(value);
    if (instance_id == 0) {
        return true;
    }

    GDExtensionObjectPtr object_value = gdcc_object_live_ptr(instance_id);
    if (object_value == NULL) {
        return true;
    }

    godot_StringName actual_class_name;
    if (!godot_object_get_class_name(object_value, class_library, &actual_class_name)) {
        return false;
    }

    godot_bool exact_match = godot_StringName_op_equal_StringName(&actual_class_name, expected_class_name);
    if (exact_match) {
        godot_StringName_destroy(&actual_class_name);
        return true;
    }
    if (!allow_subclass) {
        godot_StringName_destroy(&actual_class_name);
        return false;
    }

    godot_bool subclass_match = godot_ClassDB_is_parent_class(
        godot_ClassDB_singleton(),
        &actual_class_name,
        expected_class_name
    );
    godot_StringName_destroy(&actual_class_name);
    return subclass_match;
}

/// GDScript `is` object check for a fat-pointer pair `(raw, instance_id)`.
/// Null/freed → false (unlike `gdcc_check_variant_type_object`, which accepts null for unpack).
/// Live objects pass on exact ClassDB name match or when `expected` is a parent of the actual class.
static inline godot_bool gdcc_is_instance_of_object_raw_and_id(
        GDExtensionObjectPtr raw,
        GDObjectInstanceID instance_id,
        const godot_StringName *expected_class_name) {
    if (expected_class_name == NULL) {
        return false;
    }
    // Null ∪ freed never satisfies `x is T`.
    if (gdcc_object_is_null_raw_and_id(raw, instance_id)) {
        return false;
    }
    GDExtensionObjectPtr live = gdcc_object_live_ptr(instance_id);
    if (live == NULL) {
        return false;
    }

    godot_StringName actual_class_name;
    if (!godot_object_get_class_name(live, class_library, &actual_class_name)) {
        return false;
    }

    if (godot_StringName_op_equal_StringName(&actual_class_name, expected_class_name)) {
        godot_StringName_destroy(&actual_class_name);
        return true;
    }

    // ClassDB.is_parent_class(actual, expected) ⇔ expected is parent of actual (or equal).
    godot_bool inherits = godot_ClassDB_is_parent_class(
            godot_ClassDB_singleton(),
            &actual_class_name,
            expected_class_name
    );
    godot_StringName_destroy(&actual_class_name);
    return inherits;
}

/// GDScript `is` object check for an OBJECT Variant payload. Null/freed → false.
static inline godot_bool gdcc_is_instance_of_object_variant(
        const godot_Variant *value,
        const godot_StringName *expected_class_name) {
    if (value == NULL || expected_class_name == NULL) {
        return false;
    }
    if (godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return false;
    }
    GDObjectInstanceID instance_id = godot_variant_get_object_instance_id(value);
    if (instance_id == 0) {
        return false;
    }
    GDExtensionObjectPtr live = gdcc_object_live_ptr(instance_id);
    if (live == NULL) {
        return false;
    }
    return gdcc_is_instance_of_object_raw_and_id(live, instance_id, expected_class_name);
}

/// GDScript `as` object cast for a fat-pointer pair `(raw, instance_id)`.
/// Ownership-neutral: returns a validated live raw Godot object pointer on success, or NULL when
/// null/freed/class-mismatch. Never recovers instance_id from an unvalidated raw pointer.
/// Callers build the target-typed fat pointer (`_from_raw`) and preserve the source instance_id
/// only on the success path; failure must write the canonical null `{ptr=NULL, instance_id=0}`.
static inline GDExtensionObjectPtr gdcc_object_cast_raw_and_id(
        GDExtensionObjectPtr raw,
        GDObjectInstanceID instance_id,
        const godot_StringName *expected_class_name) {
    if (expected_class_name == NULL) {
        return NULL;
    }
    if (gdcc_object_is_null_raw_and_id(raw, instance_id)) {
        return NULL;
    }
    GDExtensionObjectPtr live = gdcc_object_live_ptr(instance_id);
    if (live == NULL) {
        return NULL;
    }
    if (!gdcc_is_instance_of_object_raw_and_id(live, instance_id, expected_class_name)) {
        return NULL;
    }
    return live;
}

/// GDScript `as` object cast for a Variant payload (OBJECT / non-OBJECT / NIL).
/// Ownership-neutral. Non-OBJECT and NIL payloads yield NULL (canonical null at the call site).
static inline GDExtensionObjectPtr gdcc_object_cast_variant(
        const godot_Variant *value,
        const godot_StringName *expected_class_name) {
    if (value == NULL || expected_class_name == NULL) {
        return NULL;
    }
    if (godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return NULL;
    }
    GDObjectInstanceID instance_id = godot_variant_get_object_instance_id(value);
    if (instance_id == 0) {
        return NULL;
    }
    GDExtensionObjectPtr live = gdcc_object_live_ptr(instance_id);
    if (live == NULL) {
        return NULL;
    }
    return gdcc_object_cast_raw_and_id(live, instance_id, expected_class_name);
}

/// True when typed-container script metadata is absent (Godot stores that as OBJECT/null, not TYPE_NIL).
static inline godot_bool gdcc_typed_script_metadata_is_null(const godot_Variant *script) {
    if (script == NULL) {
        return true;
    }
    GDExtensionVariantType script_type = godot_variant_get_type(script);
    if (script_type == GDEXTENSION_VARIANT_TYPE_NIL) {
        return true;
    }
    if (script_type != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return false;
    }
    return godot_variant_get_object_instance_id(script) == 0;
}

/// GDScript `is Array[T]` on a typed Array handle. Matches exact element builtin + class name; script leaf
/// must be null (script-leaf targets are out of MVP scope). Bare/untyped Array fails parameterized targets.
static inline godot_bool gdcc_is_instance_of_typed_array(
        const godot_Array *array,
        godot_int expected_builtin,
        const godot_StringName *expected_class_name) {
    if (array == NULL || expected_class_name == NULL) {
        return false;
    }
    if (godot_Array_get_typed_builtin(array) != expected_builtin) {
        return false;
    }
    godot_StringName actual_class_name = godot_Array_get_typed_class_name(array);
    godot_bool class_match = godot_StringName_op_equal_StringName(&actual_class_name, expected_class_name);
    godot_StringName_destroy(&actual_class_name);
    if (!class_match) {
        return false;
    }
    godot_Variant script = godot_Array_get_typed_script(array);
    godot_bool script_null = gdcc_typed_script_metadata_is_null(&script);
    godot_Variant_destroy(&script);
    return script_null;
}

/// GDScript `is Array[T]` when the value is carried as Variant.
static inline godot_bool gdcc_is_instance_of_typed_array_variant(
        const godot_Variant *value,
        godot_int expected_builtin,
        const godot_StringName *expected_class_name) {
    if (value == NULL || godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_ARRAY) {
        return false;
    }
    godot_Array array = godot_new_Array_with_Variant(value);
    godot_bool result = gdcc_is_instance_of_typed_array(&array, expected_builtin, expected_class_name);
    godot_Array_destroy(&array);
    return result;
}

/// GDScript `is Dictionary[K, V]` on a typed Dictionary handle (exact key/value metadata, null scripts).
static inline godot_bool gdcc_is_instance_of_typed_dictionary(
        const godot_Dictionary *dictionary,
        godot_int expected_key_builtin,
        const godot_StringName *expected_key_class_name,
        godot_int expected_value_builtin,
        const godot_StringName *expected_value_class_name) {
    if (dictionary == NULL || expected_key_class_name == NULL || expected_value_class_name == NULL) {
        return false;
    }
    if (godot_Dictionary_get_typed_key_builtin(dictionary) != expected_key_builtin
            || godot_Dictionary_get_typed_value_builtin(dictionary) != expected_value_builtin) {
        return false;
    }

    godot_StringName key_class = godot_Dictionary_get_typed_key_class_name(dictionary);
    godot_bool key_class_match = godot_StringName_op_equal_StringName(&key_class, expected_key_class_name);
    godot_StringName_destroy(&key_class);
    if (!key_class_match) {
        return false;
    }

    godot_StringName value_class = godot_Dictionary_get_typed_value_class_name(dictionary);
    godot_bool value_class_match = godot_StringName_op_equal_StringName(&value_class, expected_value_class_name);
    godot_StringName_destroy(&value_class);
    if (!value_class_match) {
        return false;
    }

    godot_Variant key_script = godot_Dictionary_get_typed_key_script(dictionary);
    godot_bool key_script_null = gdcc_typed_script_metadata_is_null(&key_script);
    godot_Variant_destroy(&key_script);
    if (!key_script_null) {
        return false;
    }

    godot_Variant value_script = godot_Dictionary_get_typed_value_script(dictionary);
    godot_bool value_script_null = gdcc_typed_script_metadata_is_null(&value_script);
    godot_Variant_destroy(&value_script);
    return value_script_null;
}

/// GDScript `is Dictionary[K, V]` when the value is carried as Variant.
static inline godot_bool gdcc_is_instance_of_typed_dictionary_variant(
        const godot_Variant *value,
        godot_int expected_key_builtin,
        const godot_StringName *expected_key_class_name,
        godot_int expected_value_builtin,
        const godot_StringName *expected_value_class_name) {
    if (value == NULL || godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_DICTIONARY) {
        return false;
    }
    godot_Dictionary dictionary = godot_new_Dictionary_with_Variant(value);
    godot_bool result = gdcc_is_instance_of_typed_dictionary(
            &dictionary,
            expected_key_builtin,
            expected_key_class_name,
            expected_value_builtin,
            expected_value_class_name
    );
    godot_Dictionary_destroy(&dictionary);
    return result;
}

/// Returns whether the current Variant carrier still needs outer-owner writeback.
/// Positive polarity is intentional and must stay aligned with the frontend writable-target facts:
/// - false for statically shared/reference families (`Array`, `Dictionary`, `Object`) and
///   primitive-like scalars that do not carry value-style owner writeback
/// - true for value-semantic builtin families such as `String`, `Vector*`, `Color`,
///   `Transform*`, `Callable`, `Signal`, `RID`, and `Packed*Array`
/// - default true for unlisted future Variant kinds so newly introduced value-semantic carriers do
///   not silently tunnel through runtime-gated writeback as a false negative
static godot_bool gdcc_variant_requires_writeback(const godot_Variant *value) {
    if (value == NULL) {
        return false;
    }
    switch (godot_variant_get_type(value)) {
    case GDEXTENSION_VARIANT_TYPE_NIL:
    case GDEXTENSION_VARIANT_TYPE_BOOL:
    case GDEXTENSION_VARIANT_TYPE_INT:
    case GDEXTENSION_VARIANT_TYPE_FLOAT:
    case GDEXTENSION_VARIANT_TYPE_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_DICTIONARY:
    case GDEXTENSION_VARIANT_TYPE_OBJECT:
        return false;
    case GDEXTENSION_VARIANT_TYPE_STRING:
    case GDEXTENSION_VARIANT_TYPE_VECTOR2:
    case GDEXTENSION_VARIANT_TYPE_VECTOR2I:
    case GDEXTENSION_VARIANT_TYPE_RECT2:
    case GDEXTENSION_VARIANT_TYPE_RECT2I:
    case GDEXTENSION_VARIANT_TYPE_VECTOR3:
    case GDEXTENSION_VARIANT_TYPE_VECTOR3I:
    case GDEXTENSION_VARIANT_TYPE_TRANSFORM2D:
    case GDEXTENSION_VARIANT_TYPE_VECTOR4:
    case GDEXTENSION_VARIANT_TYPE_VECTOR4I:
    case GDEXTENSION_VARIANT_TYPE_PLANE:
    case GDEXTENSION_VARIANT_TYPE_QUATERNION:
    case GDEXTENSION_VARIANT_TYPE_AABB:
    case GDEXTENSION_VARIANT_TYPE_BASIS:
    case GDEXTENSION_VARIANT_TYPE_TRANSFORM3D:
    case GDEXTENSION_VARIANT_TYPE_PROJECTION:
    case GDEXTENSION_VARIANT_TYPE_COLOR:
    case GDEXTENSION_VARIANT_TYPE_STRING_NAME:
    case GDEXTENSION_VARIANT_TYPE_NODE_PATH:
    case GDEXTENSION_VARIANT_TYPE_RID:
    case GDEXTENSION_VARIANT_TYPE_CALLABLE:
    case GDEXTENSION_VARIANT_TYPE_SIGNAL:
    case GDEXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY:
    case GDEXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY:
        return true;
    default:
        return true;
    }
}

/// @param self
/// @param method
/// @param file_name The name of the source file where the call is made, used for error reporting. If NULL, it will be treated as "<unknown>".
/// @param line_number
/// @param argv
/// @param argc
static godot_Variant godot_Variant_call(
    godot_Variant* self, const godot_StringName *method,
    const char* file_name, int line_number,
    const godot_Variant **argv, godot_int argc
) {
    godot_Variant ret;
    GDExtensionCallError error;
    godot_variant_call(self, method, (GDExtensionConstVariantPtr*) argv, argc, &ret, &error);
    if (error.error != GDEXTENSION_CALL_OK) {
        const char* src_file = file_name ? file_name : "<unknown>";
        char method_name[256];
        gdcc_string_name_to_utf8(method, method_name, sizeof(method_name));

        char desc[512];
        switch (error.error) {
        case GDEXTENSION_CALL_ERROR_INVALID_METHOD:
            snprintf(desc, sizeof(desc), "Invalid method '%s'", method_name);
            break;
        case GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT: {
            char expected_type_name[64];
            godot_String expected_str;
            godot_variant_get_type_name(error.expected, &expected_str);
            godot_string_to_utf8_chars(&expected_str, expected_type_name, sizeof(expected_type_name) - 1);
            GDExtensionInt elen = godot_String_length(&expected_str);
            if (elen >= (GDExtensionInt)sizeof(expected_type_name)) elen = sizeof(expected_type_name) - 1;
            expected_type_name[elen] = '\0';
            godot_String_destroy(&expected_str);
            snprintf(desc, sizeof(desc), "Invalid argument #%d for method '%s': expected type '%s'",
                     error.argument, method_name, expected_type_name);
            break;
        }
        case GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS:
            snprintf(desc, sizeof(desc), "Too many arguments for method '%s': expected %d, got %lld",
                     method_name, error.expected, argc);
            break;
        case GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS:
            snprintf(desc, sizeof(desc), "Too few arguments for method '%s': expected %d, got %lld",
                     method_name, error.expected, argc);
            break;
        case GDEXTENSION_CALL_ERROR_INSTANCE_IS_NULL:
            snprintf(desc, sizeof(desc), "Instance is null when calling method '%s'", method_name);
            break;
        case GDEXTENSION_CALL_ERROR_METHOD_NOT_CONST:
            snprintf(desc, sizeof(desc), "Method '%s' is not const", method_name);
            break;
        default:
            snprintf(desc, sizeof(desc), "Unknown error calling method '%s'", method_name);
            break;
        }
        godot_print_error(desc, "godot_Variant_call", src_file, line_number, true);
        return godot_new_Variant_nil();
    }
    return ret;
}

// External explicit GDCC RefCounted construction may need to delay POSTINITIALIZE until after
// the raw reference count has been established.
static GDExtensionObjectPtr gdcc_ref_counted_init_raw(GDExtensionObjectPtr obj, bool initialize) {
    if (obj == NULL) {
        return NULL;
    }
    godot_RefCounted* rc = obj;
    godot_RefCounted_init_ref(rc);
    if (initialize) {
        godot_Object_notification(obj, godot_Object_NOTIFICATION_POSTINITIALIZE(), false);
    }
    return obj;
}

#endif //GDCC_HELPER_H
