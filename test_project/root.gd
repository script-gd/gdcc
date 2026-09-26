@tool
extends Node3D

@export_tool_button("Run") var r = run;

func _ready() -> void:
    print("Test start.")

func _exit_tree() -> void:
    print("Test stop.")
    
func run():
    var arr1 := PackedStringArray(["a"]);
    append_c(arr1);
    print(arr1);

func append_c(arr: PackedStringArray):
    arr.push_back("c");
