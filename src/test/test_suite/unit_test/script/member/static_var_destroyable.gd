class_name StaticVarDestroyable
extends Node

# Destroyable static storage: typed container initializers, container method calls and
# subscript write-through, and an object slot that starts at its type default (null) and
# receives a runtime-owned value.
static var names: Array[String] = ["a"]
static var table: Dictionary = {"k": 1}
static var payload: RefCounted

# Single probe entry (one Array-returning function per arity bucket: the caller-helper naming
# surface `<class>_<argc>_arg_ret_<type>` cannot distinguish same-arity same-return methods).
# The returned array anchors, in order: pre-mutation initializer results, the object slot's
# materialized null default, post-mutation values, and a second instance observing the same
# shared storage for every slot.
func mutate(extra: String) -> Array:
    var before_size: int = names.size()
    var before_k: int = table["k"]
    var before_null: bool = payload == null
    names.append(extra)
    table["k"] = 2
    payload = RefCounted.new()
    var other := StaticVarDestroyable.new()
    return [before_size, before_k, before_null,
        names.size(), table["k"], payload != null,
        other.names.size(), other.table["k"], other.payload != null]
