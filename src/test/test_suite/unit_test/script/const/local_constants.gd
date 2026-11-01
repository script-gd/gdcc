class_name ConstLocalConstants
extends Node

const X = 10

func local_chain() -> int:
    const A = 3
    const B: int = A * 4
    var total = A + B
    return total

# A local const may read the outer class const of the same name in its own
# initializer: the new binding only becomes visible after the declaration.
func outer_fallback() -> int:
    const X = X + 1
    return X

# Local consts live in block scopes and remain read-only values that can feed
# ordinary runtime expressions in nested control flow.
func nested_scopes() -> int:
    var sum = 0
    for i in range(3):
        const STEP = 2
        sum += i * STEP
    if sum > 0:
        const BONUS = 5
        sum += BONUS
    return sum

# A local const may shadow the class const once its own declaration completes.
func local_shadow() -> int:
    const X = 99
    return X
