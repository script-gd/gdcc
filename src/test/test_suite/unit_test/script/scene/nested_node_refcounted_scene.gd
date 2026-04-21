class_name NestedNodeRefcountedSceneSmoke
extends Node

class SceneWorker extends RefCounted:
    func check_ready() -> bool:
        return get_reference_count() >= 1

class SceneChild extends Node:
    var worker: SceneWorker = SceneWorker.new()

    func attach_grandchild() -> bool:
        var grandchild_name: StringName = StringName("Grandchild")
        var grandchild_path: NodePath = NodePath("Grandchild")
        var grandchild: Node = Node.new()
        grandchild.name = grandchild_name
        self.add_child(grandchild)
        return self.has_node(grandchild_path) and worker.check_ready()

    func grandchild_class_name() -> String:
        var grandchild_path: NodePath = NodePath("Grandchild")
        return self.get_node_or_null(grandchild_path).get_class()

func inner_scene_interop_ok() -> bool:
    var child_name: StringName = StringName("SceneChild")
    var child_path: NodePath = NodePath("SceneChild")
    var grandchild_path: NodePath = NodePath("Grandchild")
    var child: SceneChild = SceneChild.new()
    var child_slot: Node = child
    child_slot.name = child_name
    self.add_child(child)

    if not self.has_node(child_path):
        return false

    if not child.attach_grandchild():
        return false

    if self.get_node_or_null(child_path).get_class() != "NestedNodeRefcountedSceneSmoke__sub__SceneChild":
        return false

    if child.grandchild_class_name() != "Node":
        return false

    return child.has_node(grandchild_path) and self.get_child_count() == 1 and child.get_child_count() == 1
