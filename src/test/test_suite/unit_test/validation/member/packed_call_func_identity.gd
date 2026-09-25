extends Node

## Validates that the compiled class preserves packed array identity sharing across the
## call_func boundary (reference semantics): mutations in either direction are visible to
## both sides. If identity broke at the wrapper (regressing to value semantics), every
## observation below would diverge.

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # typed parameter: callee push_back is visible to the interpreter caller.
    var a := PackedInt32Array([1])
    var n: int = target.call("mutate_and_count", a)
    if n != 2 or a.size() != 2 or a[1] != 7:
        push_error("Typed-param mutation not visible to caller: n=%s a=%s" % [n, str(a)])
        return

    # typed parameter append_array: shared across the same boundary.
    var extra := PackedInt32Array([8, 9])
    var m: int = target.call("append_and_count", a, extra)
    if m != 4 or a.size() != 4 or a[3] != 9:
        push_error("append_array mutation not visible to caller: m=%s a=%s" % [m, str(a)])
        return

    # Variant parameter: the packed payload shares identity through a Variant holder copy.
    var v := PackedInt32Array([1])
    var k: int = target.call("mutate_variant", v)
    if k != 2 or v.size() != 2 or v[1] != 9:
        push_error("Variant-param mutation not visible to caller: k=%s v=%s" % [k, str(v)])
        return

    # Reverse sharing: after the compiled class retains the array in a field, the alias held
    # by the interpreter still observes subsequent mutations (identity preserved).
    var b := PackedInt32Array([1])
    target.call("retain_and_touch", b)
    if b.size() != 2 or b[1] != 5:
        push_error("Retained-field mutation not visible immediately: b=%s" % str(b))
        return
    target.call("touch_retained")
    if b.size() != 3 or b[2] != 6:
        push_error("Later callee-side mutation not visible to caller alias: b=%s" % str(b))
        return

    # Return boundary identity: the retained field handed out via return is shared three-way
    # with the field itself and the caller alias; caller mutations are observable through the
    # field, and callee mutations are visible through the returned alias.
    var r: PackedInt32Array = target.call("produce_retained")
    r.push_back(11)
    if int(target.call("read_retained_size")) != 4 or b.size() != 4 or b[3] != 11:
        push_error("Returned alias mutation not visible through retained field: b=%s retained=%s" % [
            str(b),
            int(target.call("read_retained_size"))
        ])
        return
    target.call("touch_retained")
    if r.size() != 5 or r[4] != 6:
        push_error("Callee-side mutation not visible to returned alias: r=%s" % str(r))
        return

    # Freshly built return value: the callee holds no second holder, so the caller receives a
    # usable, further-mutable new array (identity independence is unobservable here and not
    # anchored; the return-boundary identity contract is anchored by the retained case above).
    var p: PackedInt32Array = target.call("produce")
    p.push_back(3)
    if p.size() != 3:
        push_error("Returned packed array not mutable on caller side: p=%s" % str(p))
        return

    print("__UNIT_TEST_PASS_MARKER__")
