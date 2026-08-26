class_name NotInMembershipSmoke
extends Node

# End-to-end anchor for `a not in b` == `not (a in b)` across typed, dynamic and
# value/condition contexts. Low bits are the expected-true cases; high bits catch
# false-positive membership negations.
func membership_mask() -> int:
    var ints: Array[int] = [1, 2, 3]
    var table := {"a": 10, "b": 20}
    var text := "hello"
    var dynamic_absent = 7
    var dynamic_present = 2
    var mask := 0

    # Typed Array[int] non-member and Dictionary/String misses (condition context).
    if 4 not in ints:
        mask += 1
    if "c" not in table:
        mask += 2
    if "z" not in text:
        mask += 4
    # Dynamic (untyped) operand takes the runtime-open evaluate path.
    if dynamic_absent not in ints:
        mask += 8
    # Value context: the published bool result is consumed by a typed variable.
    var value_result: bool = 9 not in ints
    if value_result:
        mask += 16
    # Equivalence with the hand-written `not (a in b)` form.
    if (2 not in ints) == (not (2 in ints)):
        mask += 32
    # Dynamic equivalence via a bool-typed intermediate: the dynamic `in` result is
    # unpacked to bool at the assignment boundary, then negated by the bool NOT path.
    var dynamic_in_result: bool = dynamic_present in ints
    if (dynamic_present not in ints) == (not dynamic_in_result):
        mask += 64
    if (4 not in ints) == (not (4 in ints)):
        mask += 128

    # False-positive catchers: members must NOT be reported as absent.
    if 1 not in ints:
        mask += 256
    if "a" not in table:
        mask += 512
    if "ell" not in text:
        mask += 1024
    if dynamic_present not in ints:
        mask += 2048

    return mask
