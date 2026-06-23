#ifndef GDCC_BUILTIN_CTOR_H
#define GDCC_BUILTIN_CTOR_H

#include <godot_binding.h>

/// GDCC-owned builtin constructor helpers for metadata constructors that the
/// generated Godot wrapper intentionally leaves out.
static inline godot_Variant godot_new_Nil(void) {
    return godot_new_Variant_nil();
}

static inline godot_Variant godot_new_Nil_with_Variant(const godot_Variant *value) {
    (void)value;
    return godot_new_Variant_nil();
}

static inline godot_bool godot_new_bool(void) {
    return false;
}

static inline godot_bool godot_new_bool_with_bool(godot_bool value) {
    return value;
}

static inline godot_bool godot_new_bool_with_int(godot_int value) {
    return value != 0;
}

static inline godot_bool godot_new_bool_with_float(godot_float value) {
    return value != 0.0;
}

static inline godot_int godot_new_int(void) {
    return 0;
}

static inline godot_int godot_new_int_with_int(godot_int value) {
    return value;
}

static inline godot_int godot_new_int_with_float(godot_float value) {
    return (godot_int)value;
}

static inline godot_int godot_new_int_with_bool(godot_bool value) {
    return value ? 1 : 0;
}

static inline godot_float godot_new_float(void) {
    return 0.0;
}

static inline godot_float godot_new_float_with_float(godot_float value) {
    return value;
}

static inline godot_float godot_new_float_with_int(godot_int value) {
    return (godot_float)value;
}

static inline godot_float godot_new_float_with_bool(godot_bool value) {
    return value ? 1.0 : 0.0;
}

/// Flat-float constructor shims kept as GDCC runtime helpers because Godot
/// metadata does not publish these convenience constructor shapes directly.
static inline godot_Transform2D godot_new_Transform2D_with_float_float_float_float_float_float(
    godot_float xx, godot_float xy, godot_float yx, godot_float yy, godot_float tx, godot_float ty
) {
    godot_Vector2 x = godot_new_Vector2_with_float_float(xx, xy);
    godot_Vector2 y = godot_new_Vector2_with_float_float(yx, yy);
    godot_Vector2 origin = godot_new_Vector2_with_float_float(tx, ty);
    godot_Transform2D t = godot_new_Transform2D_with_Vector2_Vector2_Vector2(&x, &y, &origin);
    return t;
}

static inline godot_Transform3D godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float(
    godot_float xx, godot_float xy, godot_float xz, godot_float yx, godot_float yy, godot_float yz, godot_float zx, godot_float zy, godot_float zz, godot_float tx, godot_float ty, godot_float tz
) {
    godot_Vector3 x = godot_new_Vector3_with_float_float_float(xx, xy, xz);
    godot_Vector3 y = godot_new_Vector3_with_float_float_float(yx, yy, yz);
    godot_Vector3 z = godot_new_Vector3_with_float_float_float(zx, zy, zz);
    godot_Vector3 origin = godot_new_Vector3_with_float_float_float(tx, ty, tz);
    godot_Transform3D t = godot_new_Transform3D_with_Vector3_Vector3_Vector3_Vector3(&x, &y, &z, &origin);
    return t;
}

static inline godot_Basis godot_new_Basis_with_float_float_float_float_float_float_float_float_float(
    godot_float xx, godot_float xy, godot_float xz, godot_float yx, godot_float yy, godot_float yz, godot_float zx, godot_float zy, godot_float zz
) {
    godot_Vector3 x = godot_new_Vector3_with_float_float_float(xx, xy, xz);
    godot_Vector3 y = godot_new_Vector3_with_float_float_float(yx, yy, yz);
    godot_Vector3 z = godot_new_Vector3_with_float_float_float(zx, zy, zz);
    godot_Basis b = godot_new_Basis_with_Vector3_Vector3_Vector3(&x, &y, &z);
    return b;
}

static inline godot_Projection godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float(
    godot_float left, godot_float right, godot_float bottom, godot_float top, godot_float z_near, godot_float z_far, godot_float fov, godot_float aspect, godot_float focal_length, godot_float fov_horizontal, godot_float fov_vertical, godot_float fov_diagonal, godot_float orthogonal_size, godot_float orthogonal_aspect, godot_float orthogonal_near, godot_float orthogonal_far
) {
    godot_Vector4 params = godot_new_Vector4_with_float_float_float_float(left, right, bottom, top);
    godot_Vector4 params2 = godot_new_Vector4_with_float_float_float_float(z_near, z_far, fov, aspect);
    godot_Vector4 params3 = godot_new_Vector4_with_float_float_float_float(focal_length, fov_horizontal, fov_vertical, fov_diagonal);
    godot_Vector4 params4 = godot_new_Vector4_with_float_float_float_float(orthogonal_size, orthogonal_aspect, orthogonal_near, orthogonal_far);
    godot_Projection p = godot_new_Projection_with_Vector4_Vector4_Vector4_Vector4(&params, &params2, &params3, &params4);
    return p;
}

#endif //GDCC_BUILTIN_CTOR_H
