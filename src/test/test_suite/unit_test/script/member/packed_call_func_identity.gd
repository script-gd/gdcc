class_name PackedCallFuncIdentity
extends Node

## Packed*Array reference-semantics call_func boundary anchor: the GDScript interpreter hands
## a packed array to the compiled class through an ordinary method call (call_func Variant
## ABI), and callee mutations must share identity with the caller; the reverse direction
## (the compiled class retains the array, the interpreter observes it later) shares identity
## as well. The ptrcall ABI exception does not apply to this path (see the runtime anchoring
## in PackedRefStorageModelSmokeTest).

var retained: PackedInt32Array

func mutate_and_count(a: PackedInt32Array) -> int:
    a.push_back(7)
    return a.size()

func append_and_count(a: PackedInt32Array, extra: PackedInt32Array) -> int:
    a.append_array(extra)
    return a.size()

func mutate_variant(a: Variant) -> int:
    a.push_back(9)
    return a.size()

func retain_and_touch(a: PackedInt32Array) -> void:
    retained = a
    retained.push_back(5)

func touch_retained() -> void:
    retained.push_back(6)

func produce_retained() -> PackedInt32Array:
    return retained

func read_retained_size() -> int:
    return retained.size()

func produce() -> PackedInt32Array:
    return PackedInt32Array([1, 2])
