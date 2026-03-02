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

#endif //GDCC_OPERATOR_H
