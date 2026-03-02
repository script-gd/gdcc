#ifndef GDCC_OPERATOR_H
#define GDCC_OPERATOR_H

static inline godot_bool gdcc_int_division_by_zero(godot_int right) {
    return right == 0;
}

static inline godot_bool gdcc_float_division_by_zero(godot_float right) {
    return right == 0.0;
}

static inline godot_bool gdcc_int_shift_right_invalid(godot_int shift) {
    godot_int bit_width = (godot_int)(sizeof(godot_int) * 8);
    return shift < 0 || shift >= bit_width;
}

static inline godot_bool gdcc_int_shift_left_invalid(godot_int value, godot_int shift) {
    if (gdcc_int_shift_right_invalid(shift)) {
        return true;
    }
    // Left-shifting negative signed integers is UB in C.
    return value < 0;
}

static godot_int pow_int(godot_int base, godot_int exp) {
    if (exp == 0) {
        return 1;
    }
    if (exp < 0) {
        if (base == 1) {
            return 1;
        }
        if (base == -1) {
            return (exp % 2 != 0) ? -1 : 1;
        }
        return 0;
    }

    __int128 result = 1;
    __int128 factor = (__int128)base;
    godot_int positive_exp = exp;
    while (positive_exp > 0) {
        if ((positive_exp & 1) != 0) {
            result *= factor;
        }
        positive_exp >>= 1;
        if (positive_exp > 0) {
            factor *= factor;
        }
    }
    return (godot_int)result;
}

static godot_bool gdcc_cmp_object(const GDExtensionObjectPtr a, const GDExtensionObjectPtr b) {
    if (a == NULL && b == NULL) {
        return true;
    }
    if (a == NULL || b == NULL) {
        return false;
    }
    const godot_int id_a = godot_Object_get_instance_id(a);
    const godot_int id_b = godot_Object_get_instance_id(b);
    return id_a == id_b;
}

#endif //GDCC_OPERATOR_H
