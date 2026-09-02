class_name GetNodeLambdaAwaitSceneSmoke
extends Node

signal tick

var phase: int = 0
var pre_name: StringName = StringName("")
var post_name: StringName = StringName("")
var connected_name: StringName = StringName("")

func _init():
    var child: Node = Node.new()
    child.name = StringName("Child")
    self.add_child(child)

    # Runtime-created nodes only register in the owner's unique-node map when the owner is
    # assigned before enabling the flag; without an owner the flag is a no-op.
    var unique: Node = Node.new()
    unique.name = StringName("Unique")
    self.add_child(unique)
    unique.owner = self
    unique.unique_name_in_owner = true

func make_await_cb() -> Callable:
    # Coroutine lambda: get-node resolution must work both before the suspension point and
    # after the resume, each time through the self capture stored in the coroutine frame.
    return func() -> int:
        pre_name = $Child.name
        phase = 1
        var ignored = await tick
        post_name = %Unique.name
        phase = 2
        return phase

func connect_unique_cb() -> void:
    tick.connect(func():
        connected_name = %Unique.name
    )

func emit_tick() -> void:
    tick.emit()
