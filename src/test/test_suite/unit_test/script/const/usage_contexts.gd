class_name ConstUsageContexts
extends Node

enum State { IDLE, RUN = 4, JUMP }

# Earlier named-enum member projections are legal inputs to class const
# initializers; the enum Dictionary itself is never a compile-time constant.
const START = State.RUN * 2

class Helper:
    const V = 6

# Ordinary qualified reads see inner-class constants; a local const initializer
# may also fold them.
func inner_qualified() -> int:
    const X = Helper.V
    return X + Helper.V

# A resolved class const is a valid compile-time match key.
func match_key(v: int) -> int:
    match v:
        START:
            return 1
        _:
            return -1

# Class consts are legal parameter-default island values.
func with_default(a: int = START) -> int:
    return a

# Lambdas read outer consts through the const-fact view without capturing them.
func lambda_reader() -> int:
    const K = 40
    var add = func() -> int:
        return K + 2
    return add.call()

# CONSTANT bindings carry no instance dependency, so static methods read them.
static func static_limit() -> int:
    return START + 1
