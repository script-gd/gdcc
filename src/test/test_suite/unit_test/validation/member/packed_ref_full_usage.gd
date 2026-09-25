extends Node

## 全用法组合的观测锚点：若 packed 在任何构造（函数调用 / 循环 / 分支 / lambda / 协程 /
## 信号）中退回值语义，聚合结果会立刻分歧。

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var control: int = target.call("run_control_flow")
    if control != 1133:
        push_error("Control-flow combination diverged: %s (expected 1133)" % control)
        return
    if int(target.call("read_field_size")) != 3:
        push_error("Field mutations through calls/loop not visible: %s" % int(target.call("read_field_size")))
        return

    var signal_lambda: int = target.call("run_signal_lambda")
    if signal_lambda != 381:
        push_error("Signal+lambda combination diverged: %s (expected 381)" % signal_lambda)
        return

    target.call("start_coroutine")
    if bool(target.call("read_coro_done")):
        push_error("Coroutine completed before resume signal.")
        return
    if int(target.call("read_field_size")) != 4:
        push_error("Pre-await coroutine mutation not visible: %s" % int(target.call("read_field_size")))
        return
    target.call("emit_resume")
    if not bool(target.call("read_coro_done")) or int(target.call("read_field_size")) != 5:
        push_error("Post-await coroutine mutation not visible: done=%s size=%s" % [
            bool(target.call("read_coro_done")),
            int(target.call("read_field_size"))
        ])
        return

    print("__UNIT_TEST_PASS_MARKER__")
