@tool
extends ConfirmationDialog

signal configured

var _service: GdccInstallService
var _manager: Node
var _java_step: LinkButton
var _compiler_step: LinkButton
var _java_check: Label
var _compiler_check: Label
var _next_button: Button
var _exit_button: LinkButton
var _step_arrow: Label
var _current_step := 0
var _java_page: VBoxContainer
var _compiler_page: VBoxContainer
var _java_path: LineEdit
var _valid_java := ""
var _java_list: ItemList
var _java_versions: Array = []
var _installed: ItemList
var _installed_versions: Array = []
var _selected_jar: Dictionary = {}
var _tabs: TabContainer
var _lists: Array[ItemList] = []
var _entries: Array = [[], []]
var _pages: Array = [1, 1]
var _loaded: Array = [false, false]
var _more: Array = [true, true]
var _buttons: Array[BaseButton] = []
var _status: Label
var _progress: ProgressBar
var _progress_text: Label
var _files: FileDialog
var _error_dialog: AcceptDialog
var _picking_java := false
var _busy := false


func setup(manager: Node) -> void:
    _manager = manager
    set_translation_domain(manager.get_translation_domain())
    auto_translate_mode = Node.AUTO_TRANSLATE_MODE_ALWAYS


func _ready() -> void:
    title = tr("Install GDCC compilation server")
    get_ok_button().hide()
    get_cancel_button().hide()
    dialog_hide_on_ok = false
    close_requested.connect(_close)
    canceled.connect(_close)
    _service = GdccInstallService.new()
    _service.report_busy = Callable(_manager.get_parent(), "_report_busy")
    add_child(_service)
    var layout := VBoxContainer.new()
    layout.add_theme_constant_override("separation", 10)
    add_child(layout)
    var timeline := HBoxContainer.new()
    timeline.add_theme_constant_override("separation", 12)
    layout.add_child(timeline)
    var java_group := HBoxContainer.new()
    java_group.add_theme_constant_override("separation", 2)
    java_group.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    timeline.add_child(java_group)
    _java_step = LinkButton.new()
    _java_step.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    _java_step.text = "Install JRE"
    _java_step.pressed.connect(func() -> void: _show_step(0))
    java_group.add_child(_java_step)
    _buttons.append(_java_step)
    _java_check = _inline_label(java_group, "✔")
    _java_check.add_theme_color_override("font_color", Color("60c689"))
    _step_arrow = _inline_label(timeline, "»")
    var compiler_group := HBoxContainer.new()
    compiler_group.add_theme_constant_override("separation", 2)
    compiler_group.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    timeline.add_child(compiler_group)
    _compiler_step = LinkButton.new()
    _compiler_step.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    _compiler_step.text = "Install GDCC"
    _compiler_step.pressed.connect(func() -> void: _show_step(1))
    compiler_group.add_child(_compiler_step)
    _buttons.append(_compiler_step)
    _compiler_check = _inline_label(compiler_group, "✔")
    _compiler_check.add_theme_color_override("font_color", Color("60c689"))
    var spacer := Control.new()
    spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    timeline.add_child(spacer)
    _exit_button = LinkButton.new()
    _exit_button.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    _exit_button.text = "Exit"
    _exit_button.pressed.connect(_close)
    timeline.add_child(_exit_button)
    var scroll := ScrollContainer.new()
    scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
    scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
    layout.add_child(scroll)
    var content := VBoxContainer.new()
    content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    scroll.add_child(content)
    _java_page = VBoxContainer.new()
    content.add_child(_java_page)
    _label(_java_page, "Select Java 25 or newer, or install the GDCC runtime.")
    _java_path = LineEdit.new()
    _java_path.placeholder_text = tr("Path to the Java executable")
    _java_path.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _java_page.add_child(_java_path)
    _java_path.text_changed.connect(func(_value: String) -> void:
        _valid_java = ""
        _update_steps())
    _java_path.text_submitted.connect(_validate_java)
    _java_list = ItemList.new()
    _java_list.custom_minimum_size.y = 130
    _java_list.auto_translate_mode = Node.AUTO_TRANSLATE_MODE_DISABLED
    _java_page.add_child(_java_list)
    _java_list.item_selected.connect(_select_java)
    var java_actions := HBoxContainer.new()
    _java_page.add_child(java_actions)
    _button(java_actions, "Choose Java...", _choose_java)
    _button(java_actions, "Check Java", _validate_java)
    _button(java_actions, "Install JRE", _install_jre)
    _compiler_page = VBoxContainer.new()
    _compiler_page.hide()
    content.add_child(_compiler_page)
    _label(_compiler_page, "Installed compilers")
    _installed = ItemList.new()
    _installed.custom_minimum_size.y = 65
    _installed.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _installed.auto_translate_mode = Node.AUTO_TRANSLATE_MODE_DISABLED
    _compiler_page.add_child(_installed)
    _installed.item_selected.connect(func(index: int) -> void:
        if not _busy:
            _selected_jar = _installed_versions[index]
            _update_steps())
    _tabs = TabContainer.new()
    _tabs.size_flags_vertical = Control.SIZE_EXPAND_FILL
    _compiler_page.add_child(_tabs)
    for index in range(2):
        var page := VBoxContainer.new()
        _tabs.add_child(page)
        _tabs.set_tab_title(index, tr("Releases") if index == 0 else tr("Previews"))
        var list := ItemList.new()
        list.auto_translate_mode = Node.AUTO_TRANSLATE_MODE_DISABLED
        list.custom_minimum_size.y = 130
        list.size_flags_vertical = Control.SIZE_EXPAND_FILL
        page.add_child(list)
        _lists.append(list)
        list.item_selected.connect(_remote_item_selected.bind(index))
    _tabs.tab_changed.connect(func(index: int) -> void:
        if not _loaded[index]:
            _load_page(index))
    var install_actions := HBoxContainer.new()
    install_actions.size_flags_vertical = Control.SIZE_SHRINK_BEGIN
    install_actions.add_theme_constant_override("separation", 12)
    _compiler_page.add_child(install_actions)
    var remote_install := _button(install_actions, "Install selected version", _install_selected)
    remote_install.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    remote_install.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    remote_install.size_flags_stretch_ratio = 2.0
    remote_install.clip_text = true
    _inline_label(install_actions, "or")
    var local_install := _button(install_actions, "Install from local...", _choose_jar)
    local_install.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    local_install.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    local_install.clip_text = true
    _progress = ProgressBar.new()
    _progress.show_percentage = false
    _progress.visible = false
    layout.add_child(_progress)
    _progress_text = _label(layout, "")
    _progress_text.auto_translate_mode = Node.AUTO_TRANSLATE_MODE_DISABLED
    _progress_text.hide()
    _status = _label(layout, "")
    _status.auto_translate_mode = Node.AUTO_TRANSLATE_MODE_DISABLED
    _status.max_lines_visible = 2
    _status.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
    _service.phase_changed.connect(func(message: String) -> void: _status.text = message)
    _next_button = _button(layout, "Next", _advance)
    _next_button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _files = FileDialog.new()
    _files.access = FileDialog.ACCESS_FILESYSTEM
    _files.file_mode = FileDialog.FILE_MODE_OPEN_FILE
    add_child(_files)
    _files.file_selected.connect(_file_selected)
    _error_dialog = AcceptDialog.new()
    _error_dialog.get_label().autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    add_child(_error_dialog)
    _update_steps()


func _label(parent: Node, text: String) -> Label:
    var label := Label.new()
    label.text = text
    label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    parent.add_child(label)
    return label


func _inline_label(parent: Node, text: String) -> Label:
    var label := Label.new()
    label.text = text
    label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
    label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
    label.size_flags_vertical = Control.SIZE_SHRINK_CENTER
    parent.add_child(label)
    return label


func _notification(what: int) -> void:
    if what == NOTIFICATION_TRANSLATION_CHANGED and is_node_ready():
        title = tr("Install GDCC compilation server")
        _java_path.placeholder_text = tr("Path to the Java executable")
        _tabs.set_tab_title(0, tr("Releases"))
        _tabs.set_tab_title(1, tr("Previews"))
        _update_steps()
        for index in range(_lists.size()):
            _rebuild_remote_list(index)


func _button(parent: Node, text: String, action: Callable) -> Button:
    var button := Button.new()
    button.text = text
    button.pressed.connect(action)
    parent.add_child(button)
    _buttons.append(button)
    return button


func open_setup() -> void:
    if not _busy:
        _show_step(0)
    # Only the selected page participates in layout before Window clamps its minimum size.
    popup_centered_clamped(Vector2i(Vector2(720, 460) * EditorInterface.get_editor_scale()), 0.8)
    if not _busy:
        _initialize()


func _initialize() -> void:
    _set_busy(true)
    _status.text = tr("Looking for Java 25+ and installed compilers...")
    await await_result(_service.initialize_proxy())
    _java_versions = await await_result(_service.scan_java())
    _refresh_java_list()
    _refresh_installed()
    _status.text = tr("Java 25+ is ready.") if not _valid_java.is_empty() else tr("Java 25+ was not found. Choose Java or install the JRE.")
    _set_busy(false)


# Native coroutines return either a completed value or a state with a completed signal.
static func await_result(result: Variant) -> Variant:
    if result is Object and result.has_signal("completed"):
        return await result.completed
    return result


func _show_step(index: int) -> void:
    if _busy or (index == 1 and _valid_java.is_empty()):
        return
    _current_step = index
    _java_page.visible = index == 0
    _compiler_page.visible = index == 1
    _update_steps()
    if index == 1 and not _loaded[_tabs.current_tab]:
        _load_page(_tabs.current_tab)


func _update_steps() -> void:
    _compiler_step.disabled = _busy or _valid_java.is_empty()
    _java_check.visible = not _valid_java.is_empty()
    _compiler_check.visible = not _selected_jar.is_empty()
    _next_button.text = tr("Next") if _current_step == 0 else tr("Finish compiler configuration")
    _next_button.disabled = _busy or _valid_java.is_empty() or (_current_step == 1 and _selected_jar.is_empty())


func _advance() -> void:
    if _current_step == 0:
        _show_step(1)
    else:
        _apply_configuration()


func _refresh_java_list(preferred: String = "") -> void:
    var selected := preferred if not preferred.is_empty() else _valid_java
    _valid_java = ""
    _java_list.clear()
    for index in range(_java_versions.size()):
        var info: Dictionary = _java_versions[index]
        _java_list.add_item(tr("Java %s · %s") % [info["major"], info["path"]])
        _java_list.set_item_tooltip(index, info["path"])
        _java_list.set_item_disabled(index, not info["usable"])
        if info["usable"] and (_valid_java.is_empty() or info["path"] == selected):
            _valid_java = info["path"]
            _java_list.select(index)
    _java_path.text = _valid_java
    _update_steps()


func _select_java(index: int) -> void:
    if not _busy and _java_versions[index]["usable"]:
        _valid_java = _java_versions[index]["path"]
        _java_path.text = _valid_java
        _status.text = tr("Java 25+ is ready.")
        _update_steps()


func _remember_java(info: Dictionary) -> void:
    for index in range(_java_versions.size()):
        if _java_versions[index]["path"] == info["path"]:
            _java_versions[index] = info
            _refresh_java_list(info["path"])
            return
    _java_versions.append(info)
    _refresh_java_list(info["path"])


func _set_busy(value: bool) -> void:
    _busy = value
    _service.set_busy(value)
    for button in _buttons:
        button.disabled = value
    _java_path.editable = not value
    if not value and _service.is_cancelled():
        _status.text = tr("Installation cancelled.")
    _progress.visible = value
    _progress.indeterminate = value
    _progress_text.visible = false
    _update_steps()


func _process(_delta: float) -> void:
    if _progress == null or not _busy:
        return
    var request := _service.http
    _progress.indeterminate = request == null or request.download_file.is_empty()
    if request != null:
        var total := maxi(request.get_body_size(), _service.download_size)
        _progress.max_value = maxi(1, total)
        _progress.value = request.get_downloaded_bytes()
        _progress_text.visible = not request.download_file.is_empty()
        _progress_text.text = tr("Downloaded %s of %s") % [String.humanize_size(request.get_downloaded_bytes()),
                String.humanize_size(total) if total > 0 else tr("Unknown size")]
    else:
        _progress_text.hide()


func _close() -> void:
    if _busy:
        _service.cancel()
    hide()


func _choose_java() -> void:
    _picking_java = true
    _files.filters = PackedStringArray(["*.exe;" + tr("Java executable")]) if OS.get_name() == "Windows" else PackedStringArray()
    _files.popup_centered_ratio(0.7)


func _choose_jar() -> void:
    _picking_java = false
    _files.filters = PackedStringArray(["*.jar;GDCC JAR"])
    _files.popup_centered_ratio(0.7)


func _file_selected(path: String) -> void:
    if _busy:
        return
    if _picking_java:
        _java_path.text = path
        _validate_java()
    else:
        _import_jar(path)


func _validate_java(_text: String = "") -> void:
    if _busy:
        return
    _set_busy(true)
    var path := _java_path.text.strip_edges().replace("\\", "/")
    if path.is_empty():
        _java_versions = await await_result(_service.scan_java())
        _refresh_java_list()
    else:
        var info: Dictionary = await await_result(_service.inspect_java(path))
        _valid_java = path if info.get("usable", false) else ""
        if not info.is_empty():
            _remember_java(info)
        if not info.get("usable", false):
            _valid_java = ""
            _java_path.text = path
            _java_list.deselect_all()
    _status.text = tr("Java 25+ is ready.") if not _valid_java.is_empty() else tr("The selected executable is not Java 25+.")
    _set_busy(false)


func _install_jre() -> void:
    if _busy:
        return
    _set_busy(true)
    _status.text = tr("Downloading and installing Java...")
    var path: String = await await_result(_service.install_jre())
    if not path.is_empty():
        var info: Dictionary = await await_result(_service.inspect_java(path))
        if not info.is_empty():
            _remember_java(info)
        _status.text = tr("Java 25+ is ready.")
    else:
        _status.text = _service.last_error
    _set_busy(false)


func _refresh_installed(preferred_path: String = "") -> void:
    _installed_versions = _service.cached_compilers()
    _installed.clear()
    _selected_jar = {}
    var saved := preferred_path
    if saved.is_empty():
        saved = str(EditorInterface.get_editor_settings().get_project_metadata("gdcc", "compiler_jar", ""))
    for index in range(_installed_versions.size()):
        var info: Dictionary = _installed_versions[index]
        _installed.add_item(str(info["version"]) + " (" + str(info["branch"]) + " " + str(info["commit"]) + ")")
        _installed.set_item_tooltip(index, info["path"])
        if info["path"] == saved:
            _installed.select(index)
            _selected_jar = info
    _update_steps()


func _load_page(index: int) -> void:
    if _busy or not _more[index]:
        return
    _set_busy(true)
    _status.text = tr("Loading compiler versions...")
    var result: Dictionary = await await_result(_service.compiler_page(index == 1, _pages[index]))
    if not result.is_empty():
        for entry in result["entries"]:
            _entries[index].append(entry)
        _pages[index] += 1
        _more[index] = result["more"]
        _loaded[index] = true
        _status.text = tr("Select a version to install.")
    else:
        _status.text = _service.last_error
    _rebuild_remote_list(index)
    _set_busy(false)


func _rebuild_remote_list(index: int) -> void:
    var list := _lists[index]
    list.clear()
    for entry in _entries[index]:
        list.add_item(entry["label"])
    if _more[index]:
        list.add_item(tr("Load more"))


func _remote_item_selected(item: int, index: int) -> void:
    if not _busy and item == _entries[index].size() and _more[index]:
        _load_page(index)


func _install_selected() -> void:
    var index := _tabs.current_tab
    var selection := _lists[index].get_selected_items()
    if _busy or selection.is_empty() or selection[0] >= _entries[index].size():
        return
    _set_busy(true)
    _status.text = tr("Downloading and installing GDCC...")
    var info: Dictionary = await await_result(_service.install_compiler(_entries[index][selection[0]]))
    _status.text = tr("The compiler is installed. Apply it to this project when ready.") if not info.is_empty() else _service.last_error
    _refresh_installed(str(info.get("path", "")))
    _set_busy(false)


func _import_jar(path: String) -> void:
    if not GdccInstallUtil.has_gdparser_library(path):
        _show_error(tr("The selected JAR needs an adjacent lib directory containing gdparser-*.jar."))
        return
    _set_busy(true)
    var info: Dictionary = await await_result(_service.import_compiler(path))
    _status.text = tr("The compiler is installed. Apply it to this project when ready.") if not info.is_empty() else _service.last_error
    _refresh_installed(str(info.get("path", "")))
    _set_busy(false)


func _show_error(message: String) -> void:
    _error_dialog.title = tr("Compiler installation error")
    _error_dialog.dialog_text = message
    _error_dialog.popup_centered_clamped.call_deferred(Vector2i(Vector2(480, 160) * EditorInterface.get_editor_scale()), 0.8)


func _apply_configuration() -> void:
    if _busy or _valid_java.is_empty() or _selected_jar.is_empty():
        return
    if _manager.is_server_connected() or not _manager.can_start_action():
        _status.text = tr("The server is connected or busy. Configuration was not changed.")
        return
    var jar := str(_selected_jar["path"]).replace("\\", "/")
    var info := GdccInstallUtil.jar_info(jar)
    if not info.is_empty():
        info["local"] = _selected_jar.get("local", false)
    if not GdccInstallUtil.check_dependencies(info):
        _status.text = tr("The compiler archive is invalid or its runtime libraries are missing.")
        return
    for path in [_valid_java, jar]:
        if path.contains("\"") or path.contains("\n") or path.contains("\r") or path.contains("{") or path.contains("}"):
            _status.text = tr("The selected path contains unsupported command characters.")
            return
    _set_busy(true)
    var result: Dictionary = await await_result(_service.run_process(_valid_java, PackedStringArray([
        "--enable-native-access=ALL-UNNAMED", "-jar", jar, "--version",
    ]), 15.0))
    if result["code"] != 0 or not await await_result(_service.check_java(_valid_java)) or _service.is_cancelled():
        _status.text = tr("The selected compiler could not run with this Java installation.")
        _set_busy(false)
        return
    if _manager.is_server_connected() or not _manager.can_start_action():
        _status.text = tr("The server is connected or busy. Configuration was not changed.")
        _set_busy(false)
        return
    var key := "gdcc/compilation_server/launch_command"
    var previous := {key: ProjectSettings.get_setting(key, ""),
            "gdcc/server/host": ProjectSettings.get_setting("gdcc/server/host", "127.0.0.1")}
    var command := '"%s" --enable-native-access=ALL-UNNAMED "-Dgdparser.gdscript.resourceDir=%s/native" -jar "%s" serve --host {host} --port {port}' \
            % [_valid_java, jar.get_base_dir(), jar]
    ProjectSettings.set_setting(key, command)
    ProjectSettings.set_setting("gdcc/server/host", "127.0.0.1")
    var error := ProjectSettings.save()
    if error != OK:
        for setting in previous:
            ProjectSettings.set_setting(setting, previous[setting])
        _status.text = tr("The project configuration could not be saved.")
        _set_busy(false)
        return
    var settings := EditorInterface.get_editor_settings()
    settings.set_project_metadata("gdcc", "java_path", _valid_java)
    settings.set_project_metadata("gdcc", "compiler_jar", jar)
    _set_busy(false)
    configured.emit()
    hide()
