extends Node

## 验证编译类经 call_func 边界保持 packed 数组身份共享（引用语义），任一方向的 mutation
## 双方可见。若身份在 wrapper 处断裂（退回值语义），下列观测会全部分歧。

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # typed 形参：callee push_back 对解释器调用方可见（§2-2）。
    var a := PackedInt32Array([1])
    var n: int = target.call("mutate_and_count", a)
    if n != 2 or a.size() != 2 or a[1] != 7:
        push_error("Typed-param mutation not visible to caller: n=%s a=%s" % [n, str(a)])
        return

    # typed 形参 append_array：同边界共享（§2-13）。
    var extra := PackedInt32Array([8, 9])
    var m: int = target.call("append_and_count", a, extra)
    if m != 4 or a.size() != 4 or a[3] != 9:
        push_error("append_array mutation not visible to caller: m=%s a=%s" % [m, str(a)])
        return

    # Variant 形参：packed payload 经 Variant 持有者拷贝共享身份（§2-16）。
    var v := PackedInt32Array([1])
    var k: int = target.call("mutate_variant", v)
    if k != 2 or v.size() != 2 or v[1] != 9:
        push_error("Variant-param mutation not visible to caller: k=%s v=%s" % [k, str(v)])
        return

    # 反向共享：编译类字段持有数组后，解释器持有的别名仍能看到后续 mutation（身份保持）。
    var b := PackedInt32Array([1])
    target.call("retain_and_touch", b)
    if b.size() != 2 or b[1] != 5:
        push_error("Retained-field mutation not visible immediately: b=%s" % str(b))
        return
    target.call("touch_retained")
    if b.size() != 3 or b[2] != 6:
        push_error("Later callee-side mutation not visible to caller alias: b=%s" % str(b))
        return

    # 返回边界身份保持：callee 经 return 交出的 retained 字段与字段本身、调用方别名三方共享
    # （§2-16 覆盖返回方向）；调用方 mutation 经字段观测可见，callee mutation 经返回别名可见。
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

    # 新建返回值：callee 未持有第二持有者，调用方得到可用、可继续 mutation 的新数组
    # （身份独立性无从观测，不作锚定；返回边界的身份合同由上方 retained 用例锚定）。
    var p: PackedInt32Array = target.call("produce")
    p.push_back(3)
    if p.size() != 3:
        push_error("Returned packed array not mutable on caller side: p=%s" % str(p))
        return

    print("__UNIT_TEST_PASS_MARKER__")
