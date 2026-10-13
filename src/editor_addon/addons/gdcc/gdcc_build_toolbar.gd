@tool
extends HBoxContainer

signal build_requested
signal settings_requested

const ICON_PATH := "res://addons/gdcc/gdcc_script.svg"
const SETTING_OPTIMIZATION_LEVEL := "gdcc/build/optimization_level"
const SETTING_STRICT_MODE := "gdcc/build/strict_mode"
const SPINNER_FRAMES := "\u280b\u2819\u2839\u2838\u283c\u2834\u2826\u2827\u2807\u280f"

var _manager: Node
var _build_button: Button
var _menu_button: Button
var _popup: PopupPanel
var _version_label: Label
var _last_build_label: Label
var _optimization: OptionButton
var _strict_mode: CheckButton
var _settings_button: Button
var _settings_dialog: AcceptDialog
var _settings_filter: LineEdit
var _settings_sections: Tree
var _spinner_index: int = 0
var _spinner_last_msec: int = 0


func setup(manager: Node) -> void:
    _manager = manager


func _ready() -> void:
    set_process(false)
    name = "GdccBuildToolbar"
    add_theme_constant_override("separation", 0)
    _build_button = Button.new()
    _build_button.text = "Build"
    _build_button.flat = true
    _build_button.focus_mode = Control.FOCUS_ACCESSIBILITY
    _build_button.theme_type_variation = "RunBarButton"
    _build_button.tooltip_text = "Build and install the GDCC project from the synchronized diagnostics snapshot."
    _build_button.pressed.connect(_on_build_pressed)
    add_child(_build_button)
    _menu_button = Button.new()
    _menu_button.flat = true
    _menu_button.focus_mode = Control.FOCUS_ACCESSIBILITY
    _menu_button.theme_type_variation = "RunBarButton"
    _menu_button.tooltip_text = "GDCC build options and project settings"
    _menu_button.pressed.connect(_open_menu)
    add_child(_menu_button)

    _popup = PopupPanel.new()
    add_child(_popup)
    var margin := MarginContainer.new()
    for side in ["left", "top", "right", "bottom"]:
        margin.add_theme_constant_override("margin_" + side, 12)
    _popup.add_child(margin)
    var content := VBoxContainer.new()
    content.custom_minimum_size.x = 300.0 * EditorInterface.get_editor_scale()
    margin.add_child(content)
    _version_label = Label.new()
    # Autowrap needs a width before the hidden popup's first minimum-height calculation.
    _version_label.custom_minimum_size.x = content.custom_minimum_size.x
    _version_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    content.add_child(_version_label)
    _last_build_label = Label.new()
    content.add_child(_last_build_label)
    content.add_child(HSeparator.new())

    var options_row := HBoxContainer.new()
    var label := Label.new()
    label.text = "Configuration"
    label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    options_row.add_child(label)
    _optimization = OptionButton.new()
    _optimization.add_item("Debug")
    _optimization.add_item("Release")
    _optimization.item_selected.connect(_on_optimization_selected)
    options_row.add_child(_optimization)
    content.add_child(options_row)
    _strict_mode = CheckButton.new()
    _strict_mode.text = "Strict Mode"
    _strict_mode.tooltip_text = "Compiler policy flag; persisted with the project's build options."
    _strict_mode.toggled.connect(_on_strict_mode_toggled)
    content.add_child(_strict_mode)
    content.add_child(HSeparator.new())
    _settings_button = Button.new()
    _settings_button.text = "GDCC Project Settings"
    _settings_button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _settings_button.pressed.connect(_open_project_settings)
    content.add_child(_settings_button)

    _manager.state_changed.connect(_refresh_state)
    ProjectSettings.settings_changed.connect(_refresh_state)
    theme_changed.connect(_apply_theme)
    _apply_theme()
    _refresh_state()


func _exit_tree() -> void:
    if ProjectSettings.settings_changed.is_connected(_refresh_state):
        ProjectSettings.settings_changed.disconnect(_refresh_state)


func _apply_theme() -> void:
    if _build_button == null:
        return
    # A PopupPanel is a Window, so it does not inherit the toolbar's editor theme.
    _popup.theme = EditorInterface.get_editor_theme()
    # SVG imports may not exist yet when the editor first enables its plugins.
    var image := Image.new()
    if image.load_svg_from_string(FileAccess.get_file_as_string(ICON_PATH), EditorInterface.get_editor_scale()) == OK:
        _build_button.icon = ImageTexture.create_from_image(image)
    _build_button.add_theme_font_override("font", get_theme_font("bold", "EditorFonts"))
    _menu_button.icon = get_theme_icon("GuiOptionArrow", "EditorIcons")
    _settings_button.icon = get_theme_icon("Tools", "EditorIcons")


func _refresh_state() -> void:
    if _build_button == null:
        return
    var enabled: bool = _manager.can_start_action()
    _build_button.disabled = not enabled
    var building: bool = _manager.is_building()
    if building and not is_processing():
        _spinner_index = 0
        _spinner_last_msec = Time.get_ticks_msec()
    set_process(building)
    _build_button.text = "Building " + SPINNER_FRAMES[_spinner_index] if building else "Build"
    _optimization.disabled = not enabled
    _strict_mode.disabled = not enabled
    _version_label.text = "GDCC: " + _manager.server_version
    _last_build_label.text = "Last successful build: " + ("Never" if _manager.last_build_time == "" else _manager.last_build_time)
    _optimization.select(1 if ProjectSettings.get_setting(SETTING_OPTIMIZATION_LEVEL, "DEBUG") == "RELEASE" else 0)
    _strict_mode.set_pressed_no_signal(bool(ProjectSettings.get_setting(SETTING_STRICT_MODE, false)))


func _process(_delta: float) -> void:
    var now := Time.get_ticks_msec()
    if now - _spinner_last_msec > 100:
        _spinner_index = (_spinner_index + 1) % SPINNER_FRAMES.length()
        _spinner_last_msec = now
        _build_button.text = "Building " + SPINNER_FRAMES[_spinner_index]


func _on_build_pressed() -> void:
    build_requested.emit()
    _manager.build_project()


func _open_menu() -> void:
    _refresh_state()
    # PopupPanel clamps the screen-space rectangle to the usable parent/screen bounds.
    _popup.popup(Rect2i(Vector2i(get_screen_position() + Vector2(0.0, size.y)), Vector2i(_popup.get_contents_minimum_size())))
    _manager.refresh_server_version()


func _on_optimization_selected(index: int) -> void:
    if not _manager.can_start_action():
        _refresh_state()
        return
    ProjectSettings.set_setting(SETTING_OPTIMIZATION_LEVEL, "DEBUG" if index == 0 else "RELEASE")
    _save_settings()


func _on_strict_mode_toggled(enabled: bool) -> void:
    if not _manager.can_start_action():
        _refresh_state()
        return
    ProjectSettings.set_setting(SETTING_STRICT_MODE, enabled)
    _save_settings()


func _save_settings() -> void:
    var error := ProjectSettings.save()
    if error != OK:
        _manager.log_message.emit("build settings could not be saved (error " + str(error) + ")")


## Godot exposes no Project Settings navigation API. Validate the known editor layout before
## opening its real dialog; never substitute the editor preferences or a second settings store.
func _open_project_settings() -> void:
    _popup.hide()
    settings_requested.emit()
    var dialogs := EditorInterface.get_base_control().find_children("*", "AcceptDialog", true, false)
    for candidate in dialogs:
        if candidate.is_class("ProjectSettingsEditor"):
            _settings_dialog = candidate
            break
    if not is_instance_valid(_settings_dialog):
        push_warning("GDCC: Project Settings dialog not found; open Project > Project Settings > GDCC manually.")
        return
    var tabs := _settings_dialog.find_children("*", "TabContainer", true, false)
    if tabs.is_empty() or tabs[0].get_child_count() == 0:
        push_warning("GDCC: Project Settings layout changed; open the GDCC category manually.")
        return
    var general: Node = tabs[0].get_child(0)
    if general.get_child_count() == 0:
        return
    var search_row := general.get_child(0)
    if not search_row is HBoxContainer or search_row.get_child_count() < 2 \
            or not search_row.get_child(0) is LineEdit or not search_row.get_child(1) is CheckButton:
        push_warning("GDCC: Project Settings filter layout changed; open the GDCC category manually.")
        return
    tabs[0].current_tab = 0
    _settings_dialog.popup_centered_clamped(Vector2i(Vector2(1200.0, 700.0) * EditorInterface.get_editor_scale()), 0.8)
    _settings_dialog.set_process_shortcut_input(true)
    var advanced := search_row.get_child(1) as CheckButton
    advanced.button_pressed = true
    _settings_filter = search_row.get_child(0) as LineEdit
    _settings_filter.text = "gdcc"
    # Programmatic LineEdit text changes do not emit the interactive filter signal.
    _settings_filter.text_changed.emit(_settings_filter.text)
    var inspectors := general.find_children("*", "SectionedInspector", true, false)
    if not inspectors.is_empty():
        var trees := inspectors[0].find_children("*", "Tree", true, false)
        if not trees.is_empty():
            _settings_sections = trees[0]
            var root := _settings_sections.get_root()
            var items: Array[TreeItem] = []
            var chosen: TreeItem = null
            if root != null:
                items.append(root)
            while not items.is_empty():
                var item := items.pop_back() as TreeItem
                var section := str(item.get_metadata(0))
                # Three-part settings create a non-selectable gdcc parent and selectable leaves.
                if item.is_selectable(0) and section.begins_with("gdcc/"):
                    if chosen == null or section == "gdcc/build":
                        chosen = item
                    if section == "gdcc/build":
                        break
                items.append_array(item.get_children())
            if chosen != null:
                chosen.select(0)
                _settings_sections.scroll_to_item(chosen)
            else:
                push_warning("GDCC: settings categories changed; choose a GDCC subcategory manually.")
    _settings_filter.grab_focus()
