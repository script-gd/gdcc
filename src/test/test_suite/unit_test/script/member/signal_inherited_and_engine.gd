class_name SignalInheritedAndEngineSmoke
extends Node

class Parent extends Node:
    signal inherited_pinged

    var parent_hits: int = 0

    func _on_inherited() -> void:
        parent_hits += 1

    func wire_inherited() -> int:
        return inherited_pinged.connect(_on_inherited)

    func fire_inherited() -> int:
        inherited_pinged.emit()
        return parent_hits

class Child extends Parent:
    func fire_as_child() -> int:
        inherited_pinged.emit()
        return parent_hits

func copy_ready(n: Node) -> Signal:
    return n.ready

func copy_pressed(button: Button) -> Signal:
    return button.pressed

func ready_matches(n: Node) -> bool:
    var sig := n.ready
    return int(sig.get_object_id()) == int(n.get_instance_id()) and String(sig.get_name()) == "ready"

func pressed_matches(button: Button) -> bool:
    var sig := button.pressed
    return int(sig.get_object_id()) == int(button.get_instance_id()) and String(sig.get_name()) == "pressed"

func make_child() -> Child:
    return Child.new()

func wire_child(child: Child) -> int:
    return child.wire_inherited()

func fire_child(child: Child) -> int:
    return child.fire_as_child()
