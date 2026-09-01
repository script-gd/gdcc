class_name GetNodeShorthandSceneSmoke
extends Node

func get_node_shorthand_ok() -> bool:
    var child: Node = Node.new()
    child.name = StringName("Child")
    self.add_child(child)

    var spaced: Node = Node.new()
    spaced.name = StringName("Name With Space")
    self.add_child(spaced)

    # Runtime-created nodes only register in the owner's unique-node map when the owner is
    # assigned before enabling the flag; without an owner the flag is a no-op.
    var unique: Node = Node.new()
    unique.name = StringName("Unique")
    self.add_child(unique)
    unique.owner = self
    unique.unique_name_in_owner = true

    var unique_spaced: Node = Node.new()
    unique_spaced.name = StringName("Unique Name")
    self.add_child(unique_spaced)
    unique_spaced.owner = self
    unique_spaced.unique_name_in_owner = true

    var plain: Node = $Child
    var spaced_hit: Node = $"Name With Space"
    var unique_hit: Node = %Unique
    var unique_spaced_hit: Node = %"Unique Name"
    if plain == null or spaced_hit == null or unique_hit == null or unique_spaced_hit == null:
        return false
    if not (plain is Node) or not (unique_hit is Node):
        return false
    # Identity anchors: a wrong unique-name registration would still return some node or fail
    # loudly, so compare the resolved names, not just non-null.
    if spaced_hit.name != StringName("Name With Space"):
        return false
    if unique_hit.name != StringName("Unique"):
        return false
    if unique_spaced_hit.name != StringName("Unique Name"):
        return false
    if self.get_node_or_null(^"Missing") != null:
        return false
    if not self.has_node(^"Child"):
        return false
    return $Child.name == StringName("Child")
