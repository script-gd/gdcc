class_name ConstNestedClasses
extends Node

# Inner classes get their own const table evaluated in source order and may
# read global constants, but not the enclosing class's unqualified members.
class Helper:
    const A = 5
    const B = A * 2
    const P = PI

class Other:
    const V = 7
    const W = Vector2(3.0, 4.0)

# A sibling inner class's method can read other inner classes' constants
# through the shared type-meta namespace.
class Reader:
    func sibling() -> int:
        return Helper.A + Other.V

# Two-level inner nesting: Deeper is only visible inside Deep's scope, so the
# nested constant is reachable through a static accessor on Deep.
class Deep:
    class Deeper:
        const N = 9

    static func read_deep() -> int:
        return Deeper.N

func inner_chain() -> int:
    return Helper.B * Helper.A

func inner_pi() -> float:
    return Helper.P

func inner_builtin() -> Vector2:
    return Other.W

func inner_in_local() -> int:
    const X = Other.V
    return X * 2

# A qualified inner-class constant is a valid compile-time match key.
func inner_match_key(v: int) -> int:
    match v:
        Other.V:
            return 1
        _:
            return -1

# Class-scoped constants (including inner-class ones) may feed parameter
# default islands.
func inner_default(a: int = Other.V) -> int:
    return a

func sibling_read() -> int:
    return Reader.new().sibling()

func deep_read() -> int:
    return Deep.read_deep()
