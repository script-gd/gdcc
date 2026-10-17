@tool
extends VBoxContainer

const SetupDialogScript := preload("res://addons/gdcc/gdcc_setup_dialog.gd")

signal settings_requested

# Inspection/log view. Project state belongs to the shared manager, not this panel.
var _manager: Node
var _log_output: TextEdit
var _status_label: Label
var _clear_button: Button
var _disconnected: VBoxContainer
var _setup_dialog: SetupDialogScript


func setup(manager: Node) -> void:
    _manager = manager
    set_translation_domain(manager.get_translation_domain())
    auto_translate_mode = Node.AUTO_TRANSLATE_MODE_ALWAYS


func _ready() -> void:
    size_flags_vertical = Control.SIZE_EXPAND_FILL
    var status_row := HBoxContainer.new()
    _status_label = Label.new()
    _status_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _status_label.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
    status_row.add_child(_status_label)
    _clear_button = Button.new()
    _clear_button.flat = true
    _clear_button.focus_mode = Control.FOCUS_ACCESSIBILITY
    _clear_button.tooltip_text = "Clear GDCC output"
    status_row.add_child(_clear_button)
    add_child(status_row)

    _log_output = TextEdit.new()
    _log_output.auto_translate_mode = Node.AUTO_TRANSLATE_MODE_DISABLED
    _log_output.editable = false
    _log_output.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _log_output.size_flags_vertical = Control.SIZE_EXPAND_FILL
    add_child(_log_output)
    _clear_button.pressed.connect(_log_output.clear)
    _disconnected = VBoxContainer.new()
    _disconnected.size_flags_vertical = Control.SIZE_EXPAND_FILL
    _disconnected.alignment = BoxContainer.ALIGNMENT_CENTER
    var settings_button := Button.new()
    settings_button.text = "Configure compilation server..."
    settings_button.pressed.connect(func() -> void: settings_requested.emit())
    _disconnected.add_child(settings_button)
    var install_button := Button.new()
    install_button.text = "Install compilation server..."
    install_button.pressed.connect(_open_setup)
    _disconnected.add_child(install_button)
    add_child(_disconnected)
    _manager.log_message.connect(_log)
    _manager.state_changed.connect(_refresh_status)
    # Child theme caches must be updated before measuring their line height.
    theme_changed.connect(_apply_theme, CONNECT_DEFERRED)
    _log_output.theme_changed.connect(_apply_theme, CONNECT_DEFERRED)
    _apply_theme()
    _refresh_status()
    var timer := Timer.new()
    timer.wait_time = 1.0
    timer.autostart = true
    timer.timeout.connect(_refresh_status)
    add_child(timer)


func _notification(what: int) -> void:
    if what == NOTIFICATION_TRANSLATION_CHANGED and is_node_ready():
        _refresh_status.call_deferred()


func _apply_theme() -> void:
    if _clear_button != null:
        _clear_button.icon = get_theme_icon("Clear", "EditorIcons")
    if _log_output != null:
        # Reserve six lines even when a long output line reveals the horizontal scrollbar.
        _log_output.custom_minimum_size.y = 6 * _log_output.get_line_height() \
                + ceilf(_log_output.get_theme_stylebox("normal").get_minimum_size().y) \
                + ceilf(_log_output.get_h_scroll_bar().get_combined_minimum_size().y)


func _refresh_status() -> void:
    _status_label.text = _manager.status_text()
    _status_label.tooltip_text = _status_label.text
    var connected: bool = _manager.is_server_connected()
    _log_output.visible = connected
    _clear_button.visible = connected
    _disconnected.visible = not connected


func _open_setup() -> void:
    if _setup_dialog == null:
        _setup_dialog = SetupDialogScript.new()
        _setup_dialog.setup(_manager)
        add_child(_setup_dialog)
        _setup_dialog.configured.connect(func() -> void: _manager.auto_setup_module())
    _setup_dialog.open_setup()


func _log(message: String) -> void:
    _log_output.text += message + "\n"
    _log_output.set_caret_line(_log_output.get_line_count() - 1)
