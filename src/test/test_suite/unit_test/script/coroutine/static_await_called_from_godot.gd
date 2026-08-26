class_name StaticAwaitCalledFromGodot
extends Node

signal release(value: int)

# Must return Variant: the ClassDB wrapper routes a suspended static coroutine through the
# Variant state-object channel; a hard-typed non-Variant return would detach instead
# (frontend_await_minicoro_plan.md step 10).
static func produce(peer: StaticAwaitCalledFromGodot) -> Variant:
    var value: int = await peer.release
    return value * 2

func emit_release(value: int) -> void:
    release.emit(value)
