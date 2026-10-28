class_name OnreadyNodeLiteralReplay
extends Node

# The outer class is a mountable harness only; the probe keeps just two lookup
# fields so the validation can swap both children at identical paths and prove
# an armed replay re-evaluates the helpers instead of reusing cached results.
class Probe extends Node:
    @onready var child_node: Node = $Child
    @onready var unique_node: Node = %Unique

func create_probe() -> Probe:
    return Probe.new()
