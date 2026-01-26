#ifndef GDCC_CALL_H
#define GDCC_CALL_H

#include <gdextension-lite.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>

static inline godot_Variant gdv_from_int(const int v) {
    godot_int i = (godot_int)v;
    return godot_new_Variant_with_int(i);
}

static inline godot_Variant gdv_from_int64(const int64_t v) {
    godot_int i = (godot_int)v;
    return godot_new_Variant_with_int(i);
}

static inline godot_Variant gdv_from_float(const float v) {
    godot_float f = (godot_float)v;
    return godot_new_Variant_with_float(f);
}

static inline godot_Variant gdv_from_double(const double v) {
    godot_float f = (godot_float)v;
    return godot_new_Variant_with_float(f);
}

static inline godot_Variant gdv_from_bool(const bool v) {
    godot_bool b = (godot_bool)v;
    return godot_new_Variant_with_bool(b);
}

static inline godot_Variant gdv_from_u8str(const char* s) {
    godot_String str = godot_new_String_with_utf8_chars(s);
    godot_Variant var = godot_new_Variant_with_String(&str);
    godot_String_destroy(&str);
    return var;
}

static inline godot_Variant gdv_from_string(const godot_String* s) {
    return godot_new_Variant_with_String(s);
}

static inline godot_Variant gdv_from_string_name(const godot_StringName* sn) {
    return godot_new_Variant_with_StringName(sn);
}

static inline godot_Variant gdv_from_vector3(const godot_Vector3* v) {
    return godot_new_Variant_with_Vector3(v);
}

static inline godot_Variant gdv_from_object(GDExtensionObjectPtr obj) {
    return godot_new_Variant_with_Object(obj);
}

#define _GDV(x) _Generic((x), \
    int: gdv_from_int, \
    int64_t: gdv_from_int64, \
    float: gdv_from_float, \
    double: gdv_from_double, \
    bool: gdv_from_bool, \
    char *: gdv_from_u8str, \
    const char *: gdv_from_u8str, \
    godot_String *: gdv_from_string, \
    const godot_String *: gdv_from_string, \
    godot_StringName *: gdv_from_string_name, \
    const godot_StringName *: gdv_from_string_name, \
    godot_Vector3 *: gdv_from_vector3, \
    const godot_Vector3 *: gdv_from_vector3, \
    godot_Object *: gdv_from_object, \
    const godot_Object *: gdv_from_object \
)(x)

static godot_Variant gdcc_callv_variants(
    const GDExtensionObjectPtr obj,
    const godot_StringName* method,
    const int argc,
    const godot_Variant** argv_variants
) {
    return godot_Object_call(obj, method, argv_variants, argc);
}

#if defined(__GNUC__) || defined(__clang__)

#define GD_CALLV0_EXPR(obj, method_sn) \
    (gdcc_callv_variants((obj), (method_sn), 0, NULL))

#define GD_CALLV_EXPR(obj, method_sn, argc, ...)                                             \
    ({                                                                                       \
        godot_Variant _gd_args[] = { __VA_ARGS__ };                                          \
        godot_Variant* _gd_arg_ptrs[argc];                                                   \
        for (int _gd_i = 0; _gd_i < argc; ++_gd_i) {                                         \
            _gd_arg_ptrs[_gd_i] = &_gd_args[_gd_i];                                          \
        }                                                                                    \
        godot_Variant _gd_ret = gdcc_callv_variants((obj), (method_sn), argc, _gd_arg_ptrs); \
        for (int _gd_i = 0; _gd_i < argc; ++_gd_i) {                                         \
            godot_Variant_destroy(&_gd_args[_gd_i]);                                         \
        }                                                                                    \
        _gd_ret;                                                                             \
    })

#define GD_OBJECT_CALL0(obj, method_sn) \
    GD_CALLV0_EXPR((obj), (method_sn), 0)

#define GD_OBJECT_CALL1(obj, method_sn, a0) \
    GD_CALLV_EXPR((obj), (method_sn), 1, _GDV(a0))

#define GD_OBJECT_CALL2(obj, method_sn, a0, a1) \
    GD_CALLV_EXPR((obj), (method_sn), 2, _GDV(a0), _GDV(a1))

#define GD_OBJECT_CALL3(obj, method_sn, a0, a1, a2) \
    GD_CALLV_EXPR((obj), (method_sn), 3, _GDV(a0), _GDV(a1), _GDV(a2))

#define GD_OBJECT_CALL4(obj, method_sn, a0, a1, a2, a3) \
    GD_CALLV_EXPR((obj), (method_sn), 4, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3))

#define GD_OBJECT_CALL5(obj, method_sn, a0, a1, a2, a3, a4) \
    GD_CALLV_EXPR((obj), (method_sn), 5, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4))

#define GD_OBJECT_CALL6(obj, method_sn, a0, a1, a2, a3, a4, a5) \
    GD_CALLV_EXPR((obj), (method_sn), 6, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5))

#define GD_OBJECT_CALL7(obj, method_sn, a0, a1, a2, a3, a4, a5, a6) \
    GD_CALLV_EXPR((obj), (method_sn), 7, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6))

#define GD_OBJECT_CALL8(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7) \
    GD_CALLV_EXPR((obj), (method_sn), 8, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7))

#define GD_OBJECT_CALL9(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8) \
    GD_CALLV_EXPR((obj), (method_sn), 9, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8))

#define GD_OBJECT_CALL10(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) \
    GD_CALLV_EXPR((obj), (method_sn), 10, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9))

#define GD_OBJECT_CALL11(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10) \
    GD_CALLV_EXPR((obj), (method_sn), 11, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10))

#define GD_OBJECT_CALL12(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) \
    GD_CALLV_EXPR((obj), (method_sn), 12, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11))

#define GD_OBJECT_CALL13(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12) \
    GD_CALLV_EXPR((obj), (method_sn), 13, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12))

#define GD_OBJECT_CALL14(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13) \
    GD_CALLV_EXPR((obj), (method_sn), 14, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12), _GDV(a13))

#define GD_OBJECT_CALL15(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14) \
    GD_CALLV_EXPR((obj), (method_sn), 15, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12), _GDV(a13), _GDV(a14))

#define GD_OBJECT_CALL16(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15) \
    GD_CALLV_EXPR((obj), (method_sn), 16, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12), _GDV(a13), _GDV(a14), _GDV(a15))

#else

static godot_Variant gd_call_variadic(
    GDExtensionObjectPtr obj,
    const godot_StringName* method,
    int argc,
    ...
) {
    if (argc <= 0) {
        return gdcc_callv_variants(obj, method, 0, NULL);
    }

    godot_Variant* args = godot_mem_alloc(sizeof(godot_Variant) * (size_t)argc);

    va_list ap;
    va_start(ap, argc);

    for (int i = 0; i < argc; ++i) {
        args[i] = va_arg(ap, godot_Variant);
    }

    va_end(ap);

    godot_Variant ret = gdcc_callv_variants(obj, method, argc, args);

    for (int i = 0; i < argc; ++i) {
        godot_Variant_destroy(&args[i]);
    }
    godot_mem_free(args);

    return ret;
}

// Easy-to-use macros: Automatically package as a Variant with _Generic at the point of call from 0 to 16

#define GD_OBJECT_CALL0(obj, method_sn) \
    gd_call_variadic((obj), (method_sn), 0)

#define GD_OBJECT_CALL1(obj, method_sn, a0) \
    gd_call_variadic((obj), (method_sn), 1, _GDV(a0))

#define GD_OBJECT_CALL2(obj, method_sn, a0, a1) \
    gd_call_variadic((obj), (method_sn), 2, _GDV(a0), _GDV(a1))

#define GD_OBJECT_CALL3(obj, method_sn, a0, a1, a2) \
    gd_call_variadic((obj), (method_sn), 3, _GDV(a0), _GDV(a1), _GDV(a2))

#define GD_OBJECT_CALL4(obj, method_sn, a0, a1, a2, a3) \
    gd_call_variadic((obj), (method_sn), 4, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3))

#define GD_OBJECT_CALL5(obj, method_sn, a0, a1, a2, a3, a4) \
    gd_call_variadic((obj), (method_sn), 5, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4))

#define GD_OBJECT_CALL6(obj, method_sn, a0, a1, a2, a3, a4, a5) \
    gd_call_variadic((obj), (method_sn), 6, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5))

#define GD_OBJECT_CALL7(obj, method_sn, a0, a1, a2, a3, a4, a5, a6) \
    gd_call_variadic((obj), (method_sn), 7, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6))

#define GD_OBJECT_CALL8(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7) \
    gd_call_variadic((obj), (method_sn), 8, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7))

#define GD_OBJECT_CALL9(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8) \
    gd_call_variadic((obj), (method_sn), 9, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8))

#define GD_OBJECT_CALL10(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) \
    gd_call_variadic((obj), (method_sn), 10, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9))

#define GD_OBJECT_CALL11(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10) \
    gd_call_variadic((obj), (method_sn), 11, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10))

#define GD_OBJECT_CALL12(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) \
    gd_call_variadic((obj), (method_sn), 12, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11))

#define GD_OBJECT_CALL13(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12) \
    gd_call_variadic((obj), (method_sn), 13, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12))

#define GD_OBJECT_CALL14(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13) \
    gd_call_variadic((obj), (method_sn), 14, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12), _GDV(a13))

#define GD_OBJECT_CALL15(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14) \
    gd_call_variadic((obj), (method_sn), 15, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12), _GDV(a13), _GDV(a14))

#define GD_OBJECT_CALL16(obj, method_sn, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15) \
    gd_call_variadic((obj), (method_sn), 16, _GDV(a0), _GDV(a1), _GDV(a2), _GDV(a3), _GDV(a4), _GDV(a5), _GDV(a6), _GDV(a7), _GDV(a8), _GDV(a9), _GDV(a10), _GDV(a11), _GDV(a12), _GDV(a13), _GDV(a14), _GDV(a15))

#endif

#endif //GDCC_CALL_H
