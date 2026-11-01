class_name ConstControlFlow
extends Node

const BASE = 10
const STEP = 3
const LIMIT = 6

# Each branch gets its own block scope, so the same const name may be
# redeclared per branch; only the taken branch's value is observed.
func branch_values(v: int) -> int:
    var out = 0
    if v > BASE:
        const TAG = BASE * 2
        out = TAG
    elif v == BASE:
        const TAG = BASE + STEP
        out = TAG
    else:
        const TAG = STEP
        out = TAG
    return out

# A for-header bound and loop-body consts are frozen values, not per-iteration
# state; break/continue still behave normally around them. The bound overshoots
# LIMIT so the break is load-bearing: without it the loop would run two more
# iterations and return 176 instead of 129.
func loop_sum() -> int:
    var sum = 0
    for i in range(LIMIT + 2):
        const BIAS = STEP + 1
        if i % 2 == 0:
            const EVEN = BIAS * 10
            sum += EVEN
            continue
        sum += i
        if sum > 100:
            break
    return sum

# While-body consts fold once like any other const declaration.
func countdown() -> int:
    var n = LIMIT
    var acc = 0
    while n > 0:
        const TICK = STEP - 1
        acc += TICK
        n -= 1
        if acc >= LIMIT:
            break
    return acc

# A match key may be a resolved class const; each section body still owns a
# block scope whose consts can feed a nested match.
func match_nested(v: int) -> int:
    match v:
        BASE:
            const HIT = 1
            return HIT
        _:
            const REST = 0
            match v % 2:
                0:
                    const EVEN_TAG = REST + STEP
                    return EVEN_TAG
                _:
                    return REST

# A nested block may shadow class consts (never a same-callable local); consts
# declared in enclosing blocks stay readable inside deeper blocks, and the
# class binding is visible again after the block ends.
func nested_shadow() -> int:
    var total = BASE
    if total > 0:
        const BASE = 2
        total += BASE * 10
        if total > 25:
            const STEP = 4
            total += BASE + STEP * 100
    total += BASE
    return total

# Consts declared on a fall-through path are scoped to that path only.
func early_exit(v: int) -> int:
    const PENALTY = 7
    if v < 0:
        return -PENALTY
    if v == 0:
        return 0
    const BONUS = PENALTY * 2
    return v + BONUS

# Runtime ternary selects between materialized const values.
func sign_of(v: int) -> int:
    return BASE if v > 0 else STEP
