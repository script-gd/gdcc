#include <godot_binding.h>
#include "gdcc_operator.h"
#include <stdio.h>
#include <stdint.h>

static int failures = 0;
static void check(const char *label, godot_int actual, godot_int expected) {
    if (actual != expected) {
        printf("FAIL %s: actual=%lld expected=%lld\n",
               label, (long long)actual, (long long)expected);
        failures++;
    }
}

int main(void) {
// @@POW_CASES@@
    if (failures == 0) {
        puts("POW_INT_OK");
    }
    return failures;
}
