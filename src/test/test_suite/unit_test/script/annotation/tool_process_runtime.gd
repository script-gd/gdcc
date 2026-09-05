@tool
class_name ToolProcessRuntimeSmoke
extends Node

# Runtime anchor for the @tool chain: a tool class compiles with the frame-loop gate omitted,
# and in game mode (editor hint off) its _process runs exactly like a non-tool class. The
# editor-side suppression itself is not observable from this headless runtime suite.
var process_count: int = 0

func _process(delta: float) -> void:
    process_count = process_count + 1

func get_process_count_value() -> int:
    return process_count
