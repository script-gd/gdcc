class_name OnreadyCtorWindowFailClosed
extends Node

# Constructor-window fail-closed anchor: a `_ready` trigger that fires before the
# constructor chain completes must skip the onready apply without latching DONE —
# overwriting not-yet-initialized derived slots would destroy garbage values.
# Forwarded user `_ready`/`_init` bodies only touch already first-written own/base
# fields, never subclass onready slots.
class Log extends RefCounted:
    static var entries: Array = []

    static func stamp(tag: String, value: int) -> int:
        entries.append(tag)
        return value

# (a) The base `_init` virtually calls `_ready` while subclass slots are unwritten.
class VirtualBase extends Node:
    @onready var base_value: int = Log.stamp("virtual_base", 11)
    var ready_calls: int = 0

    func _init() -> void:
        _ready()

    func _ready() -> void:
        ready_calls += 1

    func manual() -> void:
        _ready()

class VirtualSub extends VirtualBase:
    @onready var sub_value: int = Log.stamp("virtual_sub", 22)

# (b) `_init` fires NOTIFICATION_READY explicitly; the composite impl is entered
# before CTOR_COMPLETE is set and must fail closed (still forwarding the body).
class NotifyNode extends Node:
    @onready var value: int = Log.stamp("notify", 33)
    var ready_calls: int = 0

    func _init() -> void:
        notification(Node.NOTIFICATION_READY)

    func _ready() -> void:
        ready_calls += 1

    func manual() -> void:
        _ready()

# (c) `_init` inserts the node into an already-ready parent. That synchronous
# natural READY is consumed inside the constructor window, so after construction
# the field must stay at its type default until an explicit request_ready replay.
class AddChildNode extends Node:
    static var ready_parent: Node = null

    @onready var value: int = Log.stamp("add_child", 44)
    var ready_calls: int = 0

    func _init() -> void:
        if ready_parent != null:
            ready_parent.add_child(self)

    func _ready() -> void:
        ready_calls += 1

    func manual() -> void:
        _ready()

var virtual_node: VirtualSub
var notify_node: NotifyNode
var add_child_node: AddChildNode

func run_virtual_case() -> void:
    virtual_node = VirtualSub.new()

func attach_virtual() -> void:
    add_child(virtual_node)

func manual_virtual() -> void:
    virtual_node.manual()

func run_notify_case() -> void:
    notify_node = NotifyNode.new()

func manual_notify() -> void:
    notify_node.manual()

func attach_notify() -> void:
    add_child(notify_node)

func run_add_child_case() -> void:
    AddChildNode.ready_parent = self
    add_child_node = AddChildNode.new()
    AddChildNode.ready_parent = null

func manual_add_child() -> void:
    add_child_node.manual()

func replay_add_child() -> void:
    remove_child(add_child_node)
    add_child_node.request_ready()
    add_child(add_child_node)

func get_log() -> Array:
    return Log.entries
