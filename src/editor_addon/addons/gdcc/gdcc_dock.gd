@tool
extends VBoxContainer

# Inspection/log view. Project state belongs to the shared manager, not this panel.
var _manager: Node
var _module_input: LineEdit
var _log_output: TextEdit
var _status_label: Label
var _action_buttons: Array[Button] = []


func setup(manager: Node) -> void:
    _manager = manager


func _ready() -> void:
    _status_label = Label.new()
    _status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    add_child(_status_label)
    var config_hint := Label.new()
    config_hint.text = "Server and build options are configured in Project Settings (gdcc/*)."
    config_hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    add_child(config_hint)

    var module_row := HBoxContainer.new()
    var label := Label.new()
    label.text = "Module"
    module_row.add_child(label)
    _module_input = LineEdit.new()
    _module_input.text = "demo"
    _module_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    module_row.add_child(_module_input)
    add_child(module_row)

    var session_row := HBoxContainer.new()
    _add_button(session_row, "Ping", _manager.ping)
    _add_button(session_row, "Create", _on_create_module_pressed)
    add_child(session_row)
    var compile_row := HBoxContainer.new()
    _add_button(compile_row, "Analyze", _on_analyze_pressed)
    _add_button(compile_row, "Build", _manager.build_project)
    # Cancel remains available while a build is busy.
    _add_button(compile_row, "Cancel", _manager.cancel_build, false)
    add_child(compile_row)

    _log_output = TextEdit.new()
    _log_output.editable = false
    _log_output.custom_minimum_size = Vector2(0.0, 200.0)
    _log_output.size_flags_vertical = Control.SIZE_EXPAND_FILL
    add_child(_log_output)
    _manager.log_message.connect(_log)
    _manager.state_changed.connect(_refresh_actions)
    _manager.diagnostics_module_changed.connect(_on_diagnostics_module_changed)
    _on_diagnostics_module_changed(_manager.diagnostics_module_id)
    _refresh_actions()
    _refresh_status()
    var timer := Timer.new()
    timer.wait_time = 1.0
    timer.autostart = true
    timer.timeout.connect(_refresh_status)
    add_child(timer)


func _add_button(parent: Control, text: String, handler: Callable, tracked: bool = true) -> void:
    var button := Button.new()
    button.text = text
    button.pressed.connect(handler)
    parent.add_child(button)
    if tracked:
        _action_buttons.append(button)


func _refresh_actions() -> void:
    for button in _action_buttons:
        button.disabled = not _manager.can_start_action()


func _refresh_status() -> void:
    _status_label.text = _manager.status_text()


func _on_diagnostics_module_changed(module_id: String) -> void:
    if module_id != "":
        _module_input.text = module_id


func _log(message: String) -> void:
    _log_output.text += message + "\n"
    _log_output.set_caret_line(_log_output.get_line_count() - 1)


func _on_create_module_pressed() -> void:
    _manager.create_module(_module_input.text)


func _on_analyze_pressed() -> void:
    _manager.analyze(_module_input.text)
