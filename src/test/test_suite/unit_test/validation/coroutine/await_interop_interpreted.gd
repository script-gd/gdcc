extends Node

class InterpretedState extends RefCounted:
    signal completed(result: Variant)

class InterpretedWorker extends RefCounted:
    signal release(value: int)

    var state := InterpretedState.new()

    func start() -> InterpretedState:
        release.connect(func(value: int): state.completed.emit(value + 5), CONNECT_ONE_SHOT)
        return state

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var worker = InterpretedWorker.new()
    var state = worker.start()
    if typeof(state) != TYPE_OBJECT or not state.has_signal("completed"):
        push_error("Interpreted completed-state object was not created.")
        return

    target.call("start_consume", state)
    worker.release.emit(19)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 24:
        push_error("Compiled dynamic await did not interoperate with interpreted completed-state object.")
        return

    print("__UNIT_TEST_PASS_MARKER__")
