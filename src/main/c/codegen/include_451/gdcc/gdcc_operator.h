#ifndef GDCC_OPERATOR_H
#define GDCC_OPERATOR_H

static godot_int pow_int(godot_int base, godot_int exp) {
    godot_int res = 1;
    while (exp) {
        if (exp & 1) res *= base;
        base *= base;
        exp >>= 1;
    }
    return res;
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
