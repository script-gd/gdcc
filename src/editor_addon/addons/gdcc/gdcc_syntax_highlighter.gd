@tool
extends EditorSyntaxHighlighter

## GDScript-parity syntax highlighter for `.gd3`.
##
## The script editor auto-selects a tab's highlighter by matching the script's LANGUAGE NAME
## against each registered EditorSyntaxHighlighter's supported-languages list, and the native
## GDScript highlighter claims only "GDScript" — so `.gd3` (language "GD3") silently fell back
## to the standard highlighter and lost annotation, type-hint, member, and node-path coloring
## (4.5 script_editor_plugin.cpp selection loop). This class ports the native
## GDScriptSyntaxHighlighter token state machine (4.5-stable
## modules/gdscript/editor/gdscript_highlighter.cpp) so `.gd3` highlighting matches `.gd`.
##
## Interpreted `.gd` by necessity, not preference: compiled `.gd3` classes register with
## ClassDB at the GDExtension SCENE level, but EditorSyntaxHighlighter only exists after
## `register_editor_types()` (the EDITOR level), so a compiled class can never extend it.
##
## Instance model: `plugin.gd` registers ONE template instance via
## `ScriptEditor.register_syntax_highlighter`; registration covers only NEW tabs (the editor
## never re-runs selection for tabs that are already open), so the plugin additionally assigns
## a fresh instance to the current tab on enable and on every `editor_script_changed`. All
## mutable state lives on the created instance, never on the template.
##
## Deliberate simplifications versus the C++ original (all direction-safe — they can only
## change a color, never crash or corrupt): the ClassDB class list is not filtered by
## `is_class_exposed` (not script-bindable); identifier classification treats any non-ASCII
## code point as an identifier char; whitespace means space/tab only; the script's own member
## names AND base type are recovered by scanning the live editor buffer (the GdccScript
## member-list virtuals are bootstrap stubs that answer empty, and the engine's
## edited-resource setter is not script-bindable, so the buffer is the one reliable source).

# False until `_update_cache` has run with a text edit attached; the line callback then
# answers empty (TextEdit falls back to the default font color) instead of emitting the
# transparent unset colors.
var _configured: bool = false

var _font_color: Color = Color(0, 0, 0, 0)
var _symbol_color: Color = Color(0, 0, 0, 0)
var _function_color: Color = Color(0, 0, 0, 0)
var _function_definition_color: Color = Color(0, 0, 0, 0)
var _number_color: Color = Color(0, 0, 0, 0)
var _member_variable_color: Color = Color(0, 0, 0, 0)
var _string_color: Color = Color(0, 0, 0, 0)
var _keyword_color: Color = Color(0, 0, 0, 0)
var _control_flow_keyword_color: Color = Color(0, 0, 0, 0)
var _type_color: Color = Color(0, 0, 0, 0)
var _engine_type_color: Color = Color(0, 0, 0, 0)
var _user_type_color: Color = Color(0, 0, 0, 0)
var _annotation_color: Color = Color(0, 0, 0, 0)
var _node_path_color: Color = Color(0, 0, 0, 0)
var _node_ref_color: Color = Color(0, 0, 0, 0)
var _string_name_color: Color = Color(0, 0, 0, 0)
var _global_function_color: Color = Color(0, 0, 0, 0)

# Word -> Color lookup maps, rebuilt by `_update_cache` (native `class_names` etc.).
var _class_names: Dictionary = {}
var _reserved_keywords: Dictionary = {}
var _member_keywords: Dictionary = {}
var _global_functions: Dictionary = {}
var _comment_markers: Dictionary = {}
# ColorRegion records: {start_key, end_key, color, line_only, r_prefix, is_string,
# is_comment, is_code_region}; kept sorted by descending start_key length like the native
# `add_color_region` so `"""` outranks `"` and `##` outranks `#`.
var _color_regions: Array = []
# Cross-line region state: line -> region index still open at that line's end (-1 = none).
# Mirrors the native `color_region_cache`; same staleness behavior under edits (base class
# invalidates per-line results, this map is rebuilt line-by-line on repaint).
var _color_region_cache: Dictionary = {}


func _create() -> EditorSyntaxHighlighter:
    # Per-tab instance for the editor's native selection path; the script resource IS the
    # factory (no global class name — this is an interpreted addon script).
    return get_script().new()


func _get_name() -> String:
    return "GD3"


func _get_supported_languages() -> PackedStringArray:
    return PackedStringArray(["GD3"])


func _clear_highlighting_cache() -> void:
    _color_region_cache.clear()


## Test observability (the engine driver asserts the member scan actually ran).
func member_keyword_count() -> int:
    return _member_keywords.size()


## Test observability: whether the member map holds `word`.
func debug_has_member(word: String) -> bool:
    return _member_keywords.has(word)


func _color_setting(settings: EditorSettings, key: String) -> Color:
    var value: Variant = settings.get_setting(key)
    if value is Color:
        return value
    return Color(0, 0, 0, 0)


## Rebuilds every color/word/region table. The editor invokes `update_cache()` when the
## highlighter is installed on a tab, on theme/settings changes, and after each validation —
## the last point is what keeps the script-member scan fresh while the user edits.
func _update_cache() -> void:
    _class_names.clear()
    _reserved_keywords.clear()
    _member_keywords.clear()
    _global_functions.clear()
    _comment_markers.clear()
    _color_regions.clear()
    _color_region_cache.clear()
    _configured = false
    var te: TextEdit = get_text_edit()
    if te == null:
        return
    var settings := EditorInterface.get_editor_settings()
    _font_color = te.get_theme_color("font_color")
    _symbol_color = _color_setting(settings, "text_editor/theme/highlighting/symbol_color")
    _function_color = _color_setting(settings, "text_editor/theme/highlighting/function_color")
    _number_color = _color_setting(settings, "text_editor/theme/highlighting/number_color")
    _member_variable_color = _color_setting(settings, "text_editor/theme/highlighting/member_variable_color")
    _string_color = _color_setting(settings, "text_editor/theme/highlighting/string_color")
    _keyword_color = _color_setting(settings, "text_editor/theme/highlighting/keyword_color")
    _control_flow_keyword_color = _color_setting(settings, "text_editor/theme/highlighting/control_flow_keyword_color")
    _type_color = _color_setting(settings, "text_editor/theme/highlighting/base_type_color")
    _engine_type_color = _color_setting(settings, "text_editor/theme/highlighting/engine_type_color")
    _user_type_color = _color_setting(settings, "text_editor/theme/highlighting/user_type_color")
    _function_definition_color = _color_setting(settings, "text_editor/theme/highlighting/gdscript/function_definition_color")
    _global_function_color = _color_setting(settings, "text_editor/theme/highlighting/gdscript/global_function_color")
    _node_path_color = _color_setting(settings, "text_editor/theme/highlighting/gdscript/node_path_color")
    _node_ref_color = _color_setting(settings, "text_editor/theme/highlighting/gdscript/node_reference_color")
    _annotation_color = _color_setting(settings, "text_editor/theme/highlighting/gdscript/annotation_color")
    _string_name_color = _color_setting(settings, "text_editor/theme/highlighting/gdscript/string_name_color")
    var comment_color := _color_setting(settings, "text_editor/theme/highlighting/comment_color")
    var doc_comment_color := _color_setting(settings, "text_editor/theme/highlighting/doc_comment_color")
    var region_base := _color_setting(settings, "text_editor/theme/highlighting/folded_code_region_color")
    var code_region_color := Color(region_base.r, region_base.g, region_base.b, 1.0)
    var critical_color := _color_setting(settings, "text_editor/theme/highlighting/comment_markers/critical_color")
    var warning_color := _color_setting(settings, "text_editor/theme/highlighting/comment_markers/warning_color")
    var notice_color := _color_setting(settings, "text_editor/theme/highlighting/comment_markers/notice_color")

    # Engine types. The C++ original filters by `ClassDB::is_class_exposed`, which is not
    # script-bindable; unexposed names are editor-internal and practically never collide with
    # user identifiers, so the filter is dropped.
    for class_name_entry in ClassDB.get_class_list():
        _class_names[class_name_entry] = _engine_type_color
    # Global enums (CoreConstants is not script-bindable; list pinned to 4.5 @GlobalScope).
    for enum_name in [
        "ClockDirection", "Corner", "Error", "EulerOrder", "HorizontalAlignment",
        "InlineAlignment", "JoyAxis", "JoyButton", "Key", "KeyLocation", "KeyModifierMask",
        "MethodFlags", "MIDIMessage", "MouseButton", "MouseButtonMask", "Orientation",
        "PropertyHint", "PropertyUsageFlags", "Side", "VerticalAlignment",
    ]:
        _class_names[enum_name] = _engine_type_color
    # User types (script global classes) and autoload singletons. The autoload list itself is
    # not script-bindable, so singletons are recovered from the `autoload/*` project settings:
    # a value starting with '*' means enabled.
    for global_class in ProjectSettings.get_global_class_list():
        var global_name := str(global_class.get("class", ""))
        if global_name != "":
            _class_names[global_name] = _user_type_color
    for property in ProjectSettings.get_property_list():
        var property_name := str(property.get("name", ""))
        if property_name.begins_with("autoload/"):
            var autoload_value := str(ProjectSettings.get_setting(property_name, ""))
            if autoload_value.begins_with("*"):
                _class_names[property_name.substr(9)] = _user_type_color
    # Core types: base ScriptLanguage::get_core_type_words (GDScript does not override it)
    # plus the primitives the native highlighter appends.
    for core_type in [
        "String", "Vector2", "Vector2i", "Rect2", "Rect2i", "Vector3", "Vector3i",
        "Transform2D", "Vector4", "Vector4i", "Plane", "Quaternion", "AABB", "Basis",
        "Transform3D", "Projection", "Color", "StringName", "NodePath", "RID", "Callable",
        "Signal", "Dictionary", "Array", "PackedByteArray", "PackedInt32Array",
        "PackedInt64Array", "PackedFloat32Array", "PackedFloat64Array", "PackedStringArray",
        "PackedVector2Array", "PackedVector3Array", "PackedColorArray", "PackedVector4Array",
        "Variant", "void", "bool", "int", "float",
    ]:
        _class_names[core_type] = _type_color

    # Reserved words: GDScript's own 4.5 list (the surface syntax `.gd3` shares), NOT the
    # smaller list `GdccScriptLanguage._get_reserved_words` reports — the native highlighter
    # colors against the GDScript list, and `assert`/`preload`/`void` are pre-empted by the
    # global-function and class-name maps at lookup time just like upstream.
    var control_flow := [
        "break", "continue", "elif", "else", "for", "if", "match", "pass", "return", "when",
        "while",
    ]
    var plain_keywords := [
        "class", "class_name", "const", "enum", "extends", "func", "namespace", "signal",
        "static", "trait", "var", "await", "breakpoint", "self", "super", "yield",
        "and", "as", "in", "is", "not", "or", "false", "null", "true",
        "INF", "NAN", "PI", "TAU", "assert", "preload", "void",
    ]
    for keyword in control_flow:
        _reserved_keywords[keyword] = _control_flow_keyword_color
    for keyword in plain_keywords:
        _reserved_keywords[keyword] = _keyword_color
    # Native special case: `set`/`get` as "keywords" with the function color.
    _reserved_keywords["set"] = _function_color
    _reserved_keywords["get"] = _function_color

    # Global functions: Variant utility functions (4.5 @GlobalScope) + GDScript's own utility
    # functions (`_char` is script-visible as `char`) + assert/preload.
    for global_function in [
        "abs", "absf", "absi", "acos", "acosh", "angle_difference", "asin", "asinh", "atan",
        "atan2", "atanh", "bezier_derivative", "bezier_interpolate", "bytes_to_var",
        "bytes_to_var_with_objects", "ceil", "ceilf", "ceili", "clamp", "clampf", "clampi",
        "cos", "cosh", "cubic_interpolate", "cubic_interpolate_angle",
        "cubic_interpolate_angle_in_time", "cubic_interpolate_in_time", "db_to_linear",
        "deg_to_rad", "ease", "error_string", "exp", "floor", "floorf", "floori", "fmod",
        "fposmod", "hash", "instance_from_id", "inverse_lerp", "is_equal_approx", "is_finite",
        "is_inf", "is_instance_id_valid", "is_instance_valid", "is_nan", "is_same",
        "is_zero_approx", "lerp", "lerp_angle", "lerpf", "linear_to_db", "log", "max", "maxf",
        "maxi", "min", "minf", "mini", "move_toward", "nearest_po2", "pingpong", "posmod",
        "pow", "print", "print_rich", "print_verbose", "printerr", "printraw", "prints",
        "printt", "push_error", "push_warning", "rad_to_deg", "rand_from_seed", "randf",
        "randf_range", "randfn", "randi", "randi_range", "randomize", "remap",
        "rid_allocate_id", "rid_from_int64", "rotate_toward", "round", "roundf", "roundi",
        "seed", "sign", "signf", "signi", "sin", "sinh", "smoothstep", "snapped", "snappedf",
        "snappedi", "sqrt", "step_decimals", "str", "str_to_var", "tan", "tanh",
        "type_convert", "type_string", "typeof", "var_to_bytes", "var_to_bytes_with_objects",
        "var_to_str", "weakref", "wrap", "wrapf", "wrapi",
        "convert", "type_exists", "char", "ord", "range", "load", "inst_to_dict",
        "dict_to_inst", "Color8", "print_debug", "print_stack", "get_stack", "len",
        "is_instance_of", "assert", "preload",
    ]:
        _global_functions[global_function] = true

    # Regions in native registration order; `_add_color_region` keeps the list sorted by
    # descending start-key length, which is what makes `##` beat `#` and triples beat singles.
    _add_color_region("#", "", comment_color, true, false, false, true, false)
    _add_color_region("##", "", doc_comment_color, true, false, false, true, false)
    _add_color_region("#region", "", code_region_color, true, false, false, true, true)
    _add_color_region("#endregion", "", code_region_color, true, false, false, true, true)
    # Regular quoted strings do NOT terminate at a newline in the 4.5 tokenizer, so none of
    # the string regions are line-only.
    _add_color_region("\"", "\"", _string_color, false, false, true, false, false)
    _add_color_region("'", "'", _string_color, false, false, true, false, false)
    _add_color_region("\"\"\"", "\"\"\"", _string_color, false, false, true, false, false)
    _add_color_region("'''", "'''", _string_color, false, false, true, false, false)
    _add_color_region("\"", "\"", _string_color, false, true, true, false, false)
    _add_color_region("'", "'", _string_color, false, true, true, false, false)
    _add_color_region("\"\"\"", "\"\"\"", _string_color, false, true, true, false, false)
    _add_color_region("'''", "'''", _string_color, false, true, true, false, false)

    # Comment markers
    _load_comment_markers(settings, "text_editor/theme/highlighting/comment_markers/critical_list", critical_color)
    _load_comment_markers(settings, "text_editor/theme/highlighting/comment_markers/warning_list", warning_color)
    _load_comment_markers(settings, "text_editor/theme/highlighting/comment_markers/notice_list", notice_color)

    # Members: the script's OWN top-level names plus its base class's ClassDB entries. Both
    # come from scanning the live buffer — the GdccScript member-list virtuals are bootstrap
    # stubs that answer empty, and `_set_edited_resource` is not script-bindable, so the
    # buffer is the only reliable source (it also tracks unsaved edits; the scan cadence
    # follows `update_cache` — install, theme change, validate). A quoted or project-class
    # base simply yields empty ClassDB lists; no `extends` header means RefCounted, matching
    # GdccScript's own header parse.
    var base_type := _scan_script_source(te.text)
    if base_type == "":
        base_type = "RefCounted"
    if ClassDB.class_exists(base_type):
        for property_entry in ClassDB.class_get_property_list(base_type):
            var member_name := str(property_entry.get("name", ""))
            # PROPERTY_USAGE_GROUP(64)|CATEGORY(128)|SUBGROUP(256): header rows only.
            if int(property_entry.get("usage", 0)) & 448 != 0:
                continue
            if member_name.contains("/"):
                continue
            _member_keywords[member_name] = _member_variable_color
        for signal_entry in ClassDB.class_get_signal_list(base_type):
            _member_keywords[str(signal_entry.get("name", ""))] = _member_variable_color
        for method_entry in ClassDB.class_get_method_list(base_type):
            _member_keywords[str(method_entry.get("name", ""))] = _member_variable_color
        for constant_name in ClassDB.class_get_integer_constant_list(base_type):
            _member_keywords[constant_name] = _member_variable_color
        for builtin_enum in ClassDB.class_get_enum_list(base_type):
            _member_keywords[builtin_enum] = _engine_type_color
    _configured = true


func _load_comment_markers(settings: EditorSettings, setting_key: String, color: Color) -> void:
    var list_value := str(settings.get_setting(setting_key))
    for word in list_value.split(",", false):
        if word != "":
            _comment_markers[word] = color


## Region registration with the native ordering rule: descending start-key length, a
## same-length newcomer goes BEFORE the existing entry.
func _add_color_region(start_key: String, end_key: String, color: Color, line_only: bool, r_prefix: bool, is_string: bool, is_comment: bool, is_code_region: bool) -> void:
    var at := 0
    for existing in _color_regions:
        if start_key.length() < str(existing["start_key"]).length():
            at += 1
        else:
            break
    _color_regions.insert(at, {
        "start_key": start_key,
        "end_key": end_key,
        "color": color,
        "line_only": line_only,
        "r_prefix": r_prefix,
        "is_string": is_string,
        "is_comment": is_comment,
        "is_code_region": is_code_region,
    })


## Line highlighter entry (base-class cached, editor-driven). Faithful port of the native
## `_get_line_syntax_highlighting_impl`; the block comments reference its phases.
func _get_line_syntax_highlighting(line: int) -> Dictionary:
    var color_map: Dictionary = {}
    if not _configured:
        return color_map
    var te: TextEdit = get_text_edit()
    if te == null or line < 0 or line >= te.get_line_count():
        return color_map

    # Transition-tracker type identities (the native enum is only ever compared).
    var T_NONE := 0
    var T_REGION := 1
    var T_KEYWORD := 2
    var T_SIGNAL := 3
    var T_FUNCTION := 4
    var T_NUMBER := 5
    var T_SYMBOL := 6
    var T_TYPE := 7
    var T_MEMBER := 8
    var T_IDENTIFIER := 9
    var T_NODE_PATH := 10
    var T_NODE_REF := 11
    var T_ANNOTATION := 12
    var T_STRING_NAME := 13

    var next_type := T_NONE
    var current_type := T_NONE
    var prev_type := T_NONE

    var prev_text := ""
    var prev_column := 0
    var prev_is_char := false
    var prev_is_digit := false
    var prev_is_binary_op := false

    var in_keyword := false
    var in_word := false
    var in_number := false
    var in_node_path := false
    var in_node_ref := false
    var in_annotation := false
    var in_string_name := false
    var is_hex_notation := false
    var is_bin_notation := false
    var in_member_variable := false
    var in_lambda := false

    var in_function_name := false
    var in_function_declaration := false
    var in_signal_declaration := false
    var is_after_func_signal_declaration := false
    var in_var_const_declaration := false
    var is_after_var_const_declaration := false
    var expect_type := false

    var in_declaration_params := 0
    var in_declaration_param_dicts := 0
    var in_type_params := 0

    var keyword_color := Color(0, 0, 0, 0)
    var color := Color(0, 0, 0, 0)

    # Cross-line region state: recover the region left open by the previous line, backfilling
    # through the base class's per-line cache when earlier lines have not been computed yet
    # (out-of-order requests from the painter are normal).
    _color_region_cache[line] = -1
    var in_region := -1
    if line != 0:
        var prev_region_line: int = line - 1
        while prev_region_line > 0 and not _color_region_cache.has(prev_region_line):
            prev_region_line -= 1
        var backfill := prev_region_line
        while backfill < line - 1:
            get_line_syntax_highlighting(backfill)
            backfill += 1
        if not _color_region_cache.has(line - 1):
            get_line_syntax_highlighting(line - 1)
        in_region = int(_color_region_cache[line - 1])

    var text: String = te.get_line(line)
    var line_length: int = text.length()
    var prev_color := Color(0, 0, 0, 0)

    if in_region != -1 and line_length == 0:
        _color_region_cache[line] = in_region
    var j := 0
    while j < line_length:
        color = _font_color
        var cj := _char_at(text, j)
        var is_char: bool = not _is_symbol(cj)
        var is_a_symbol: bool = _is_symbol(cj)
        var is_a_digit: bool = _is_digit(cj)
        var is_binary_op := false

        # --- Color regions (strings/comments/code regions) --------------------------
        if is_a_symbol or in_region != -1:
            var from := j
            if in_region == -1:
                # Skip escaped characters: a `\"` pair must not be read as a region start.
                while from < line_length:
                    if _char_at(text, from) == 92:
                        from += 2
                        continue
                    break

            if from != line_length:
                if in_region == -1:
                    var r_prefix: bool = from > 0 and _char_at(text, from - 1) == 114  # 'r'
                    var region_index := 0
                    while region_index < _color_regions.size():
                        var candidate: Dictionary = _color_regions[region_index]
                        var start_key: String = candidate["start_key"]
                        var end_key: String = candidate["end_key"]
                        if line_length - from < start_key.length():
                            region_index += 1
                            continue
                        if candidate["is_string"] and candidate["r_prefix"] != r_prefix:
                            region_index += 1
                            continue
                        var matched := true
                        var key_index := 0
                        while key_index < start_key.length():
                            if start_key.unicode_at(key_index) != _char_at(text, from + key_index):
                                matched = false
                                break
                            key_index += 1
                        if matched and candidate["is_code_region"]:
                            # `#region`/`#endregion` highlight only as the line's first word.
                            var first_word := _first_word(text.strip_edges())
                            if first_word != "#region" and first_word != "#endregion":
                                matched = false
                        if not matched:
                            region_index += 1
                            continue
                        in_region = region_index
                        from += start_key.length()
                        # Whole-line case: no end key, line-only region, or the end key can no
                        # longer fit on this line.
                        if end_key.length() == 0 or candidate["line_only"] or from + end_key.length() > line_length:
                            if candidate["is_comment"]:
                                # Do NOT consume the line: comments fall through to the marker
                                # pass below.
                                break
                            if from + end_key.length() > line_length:
                                # Unterminated string: keep scanning when a backslash remains
                                # so escape sequences still get the symbol color.
                                if text.find("\\", from) >= 0:
                                    break
                            prev_color = candidate["color"]
                            color_map[j] = {"color": candidate["color"]}
                            j = line_length
                            if not candidate["line_only"]:
                                _color_region_cache[line] = region_index
                        break
                    if j == line_length and not _color_regions[in_region]["is_comment"]:
                        j += 1
                        continue

                if in_region != -1:
                    var region: Dictionary = _color_regions[in_region]
                    var region_color: Color = region["color"]
                    if in_node_path and region["is_string"]:
                        region_color = _node_path_color
                    if in_node_ref and region["is_string"]:
                        region_color = _node_ref_color
                    if in_string_name and region["is_string"]:
                        region_color = _string_name_color
                    prev_color = region_color
                    color_map[j] = {"color": region_color}

                    if region["is_comment"]:
                        # Comment-marker pass: whole words found in the configured lists get
                        # their own color; the comment color resumes after each.
                        var marker_start := from
                        var marker_len := 0
                        while from <= line_length:
                            if from < line_length and _is_identifier_continue(_char_at(text, from)):
                                marker_len += 1
                            else:
                                if marker_len > 0:
                                    var marker_word := text.substr(marker_start, marker_len)
                                    if _comment_markers.has(marker_word):
                                        color_map[marker_start] = {"color": _comment_markers[marker_word]}
                                        color_map[from] = {"color": region_color}
                                marker_start = from + 1
                                marker_len = 0
                            from += 1
                        from = line_length - 1
                        j = from
                    else:
                        # Find the end key; backslash escapes keep the symbol color and skip
                        # the escaped char, with the `\uXXXX`/`\UXXXXXX` tail highlighted as
                        # part of the region (native behavior).
                        var region_end_index := -1
                        var end_key: String = region["end_key"]
                        var end_key_length: int = end_key.length()
                        while from < line_length:
                            if line_length - from < end_key_length:
                                if text.find("\\", from) < 0:
                                    break
                            var cf := _char_at(text, from)
                            if _is_symbol(cf):
                                if cf == 92:
                                    # The char after a backslash is skipped even in RAW
                                    # strings (only the escape COLORING is raw-gated) — this
                                    # matches the tokenizer, which consumes `\"` and `\\` as
                                    # content in raw mode (gdscript_tokenizer.cpp string()).
                                    if not region["r_prefix"]:
                                        color_map[from] = {"color": _symbol_color}
                                    from += 1
                                    if not region["r_prefix"]:
                                        var escape_hex_len := 0
                                        var escaped := _char_at(text, from)
                                        if escaped == 117:  # 'u'
                                            escape_hex_len = 4
                                        elif escaped == 85:  # 'U'
                                            escape_hex_len = 6
                                        var hex_index := 0
                                        while hex_index < escape_hex_len and from < line_length - 1:
                                            if not _is_hex_digit(_char_at(text, from + 1)):
                                                break
                                            from += 1
                                            hex_index += 1
                                        color_map[from + 1] = {"color": region_color}
                                    from += 1
                                    continue
                                var candidate_end := from
                                var end_index := 0
                                while end_index < end_key_length:
                                    if end_key.unicode_at(end_index) != _char_at(text, from + end_index):
                                        candidate_end = -1
                                        break
                                    end_index += 1
                                if candidate_end != -1:
                                    region_end_index = from
                                    break
                            from += 1
                        j = from + (end_key_length - 1)
                        if region_end_index == -1:
                            _color_region_cache[line] = in_region

                    prev_type = T_REGION
                    prev_text = ""
                    prev_column = j
                    in_region = -1
                    prev_is_char = false
                    prev_is_digit = false
                    prev_is_binary_op = false
                    j += 1
                    continue

        # --- Binary-operator heuristic ------------------------------------------------
        # Decides whether `& ^ % + - ~ .` act as binary operators (matters for unary-number
        # starts, StringName/NodePath/NodeRef prefixes) by looking at the previous word.
        if j > 0 and (cj == 38 or cj == 94 or cj == 37 or cj == 43 or cj == 45 or cj == 126 or cj == 46):
            var word_end := j - 1
            while word_end > 0 and _is_whitespace(_char_at(text, word_end)):
                word_end -= 1
            var word_begin := word_end
            while word_begin > 0 and not _is_symbol(_char_at(text, word_begin)):
                word_begin -= 1
            var prev_word := text.substr(word_begin + 1, word_end - word_begin)
            # Keywords block binary treatment, except the ones that denote values.
            if not _reserved_keywords.has(prev_word) or _is_value_keyword(prev_word):
                var end_char := _char_at(text, word_end)
                if not _is_symbol(end_char) or end_char == 34 or end_char == 39 or end_char == 41 or end_char == 93 or end_char == 125:
                    is_binary_op = true

        if not is_char:
            in_keyword = false

        # --- Number state -------------------------------------------------------------
        if is_hex_notation and (_is_hex_digit(cj) or is_a_digit):
            is_a_digit = true
        elif cj != 95:  # '_'
            is_hex_notation = false
        if is_bin_notation and not _is_binary_digit(cj):
            is_a_digit = false
            is_bin_notation = false

        if not in_number and not in_word and is_a_digit:
            in_number = true

        if in_number and not is_a_digit:
            var previous_char := _char_at(text, j - 1)
            if (cj == 98 or cj == 66) and previous_char == 48:  # 0b/0B
                is_bin_notation = true
            elif (cj == 120 or cj == 88) and previous_char == 48:  # 0x/0X
                is_hex_notation = true
            elif not ((cj == 45 or cj == 43) and (previous_char == 101 or previous_char == 69) and not prev_is_digit) \
                    and not (cj == 95 and (prev_is_digit or previous_char == 98 or previous_char == 66 or previous_char == 120 or previous_char == 88 or previous_char == 46)) \
                    and not ((cj == 101 or cj == 69) and (prev_is_digit or previous_char == 95)) \
                    and not (cj == 46 and (prev_is_digit or (not prev_is_binary_op and (previous_char == 95 or previous_char == 45 or previous_char == 43 or previous_char == 126)))) \
                    and not ((cj == 45 or cj == 43 or cj == 126) and not is_binary_op and not prev_is_binary_op and previous_char != 101 and previous_char != 69):
                # Number continues through: exponent signs, '_' separators, 'e' notation,
                # inner/leading decimal points, and stacked unary signs.
                in_number = false
        elif cj == 46 and not is_binary_op and _is_digit(_char_at(text, j + 1)) and (j == 0 or (j > 0 and _char_at(text, j - 1) != 46)):
            # Leading decimal point (`.42`).
            in_number = true
        elif (cj == 45 or cj == 43 or cj == 126) and not is_binary_op:
            # Unary signs start a number only when a digit (or dotted digit) follows.
            var non_op := j + 1
            while _char_at(text, non_op) == 45 or _char_at(text, non_op) == 43 or _char_at(text, non_op) == 126:
                non_op += 1
            if _is_digit(_char_at(text, non_op)) or (_char_at(text, non_op) == 46 and non_op < line_length and _is_digit(_char_at(text, non_op + 1))):
                in_number = true

        # --- Word state -----------------------------------------------------------------
        if not in_word and _is_identifier_start(cj) and not in_number:
            in_word = true
        if is_a_symbol and cj != 46 and in_word:
            in_word = false

        # --- Keyword/class/member/global-function classification -----------------------
        if not in_keyword and is_char and not prev_is_char:
            var word_end2 := j
            while word_end2 < line_length and not _is_symbol(_char_at(text, word_end2)):
                word_end2 += 1
            var word := text.substr(j, word_end2 - j)
            var word_color := Color(0, 0, 0, 0)
            var word_color_found := false
            if _global_functions.has(word):
                # `assert`/`preload` highlight without a following bracket; the rest need it.
                if word == "assert" or word == "preload":
                    word_color = _global_function_color
                    word_color_found = true
                else:
                    var bracket := word_end2
                    while bracket < line_length and _is_whitespace(_char_at(text, bracket)):
                        bracket += 1
                    if _char_at(text, bracket) == 40:
                        word_color = _global_function_color
                        word_color_found = true
            elif _class_names.has(word):
                word_color = _class_names[word]
                word_color_found = true
            elif _reserved_keywords.has(word):
                word_color = _reserved_keywords[word]
                word_color_found = true
                # Don't highlight `list` as a type in `for elem: Type in list`.
                expect_type = false
            elif _member_keywords.has(word):
                word_color = _member_keywords[word]
                word_color_found = true
                in_member_variable = true

            if word_color_found:
                # Keyword, member & global-func coloring is not allowed after a dot.
                var lookback := j - 1
                while lookback >= 0:
                    var lookback_char := _char_at(text, lookback)
                    if lookback_char == 46:
                        word_color_found = false
                        break
                    elif lookback_char > 32:
                        break
                    lookback -= 1
                if not in_member_variable and word_color_found:
                    in_keyword = true
                    keyword_color = word_color

        # --- Function / signal / declaration-name detection -----------------------------
        if not in_function_name and in_word and not in_keyword:
            if prev_text == "signal":
                in_signal_declaration = true
            else:
                var probe := j
                while probe < line_length and not _is_symbol(_char_at(text, probe)) and not _is_whitespace(_char_at(text, probe)):
                    probe += 1
                while probe < line_length and _is_whitespace(_char_at(text, probe)):
                    probe += 1
                if _char_at(text, probe) == 40:
                    in_function_name = true
                    if prev_text == "func":
                        in_function_declaration = true
                elif prev_text == "var" or prev_text == "for" or prev_text == "const":
                    in_var_const_declaration = true
                # Lambda: `func name(...)` right after a ':'.
                if in_function_declaration:
                    var before := j - 1
                    while before > 0 and _is_whitespace(_char_at(text, before)):
                        before -= 1
                    if _char_at(text, before) == 58:
                        in_lambda = true

        # --- Member-variable detection after a single dot --------------------------------
        if not in_function_name and not in_member_variable and not in_keyword and not in_number and in_word:
            var before_word := j
            while before_word > 0 and not _is_symbol(_char_at(text, before_word)) and not _is_whitespace(_char_at(text, before_word)):
                before_word -= 1
            if _char_at(text, before_word) == 46 and (before_word < 1 or _char_at(text, before_word - 1) != 46):
                in_member_variable = true

        # --- Symbol-driven state updates ---------------------------------------------------
        if is_a_symbol:
            if in_function_declaration or in_signal_declaration:
                is_after_func_signal_declaration = true
            if in_var_const_declaration:
                is_after_var_const_declaration = true

            if in_declaration_params > 0:
                if cj == 40:
                    in_declaration_params += 1
                elif cj == 41:
                    in_declaration_params -= 1
                elif cj == 123:
                    in_declaration_param_dicts += 1
                elif cj == 125:
                    in_declaration_param_dicts -= 1
            elif (is_after_func_signal_declaration or prev_text == "func") and cj == 40:
                in_declaration_params = 1
                in_declaration_param_dicts = 0

            if expect_type:
                if cj == 91:
                    in_type_params += 1
                elif cj == 93:
                    in_type_params -= 1
                elif cj == 44:
                    if in_type_params <= 0:
                        expect_type = false
                elif cj == 32 or cj == 9 or cj == 46:
                    pass
                else:
                    expect_type = false
            else:
                if j > 0 and _char_at(text, j - 1) == 45 and cj == 62:  # `->`
                    expect_type = true
                    in_type_params = 0
                # `:` in a var/const declaration or in top-level func/signal params.
                if (is_after_var_const_declaration or (in_declaration_params == 1 and in_declaration_param_dicts == 0)) and cj == 58:
                    expect_type = true
                    in_type_params = 0

            in_function_name = false
            in_function_declaration = false
            in_signal_declaration = false
            in_var_const_declaration = false
            in_lambda = false
            in_member_variable = false

            if not _is_whitespace(cj):
                is_after_func_signal_declaration = false
                is_after_var_const_declaration = false

        # --- StringName / NodePath / NodeRef / annotation prefixes ------------------------
        if not in_string_name and in_region == -1 and cj == 38 and not is_binary_op:  # '&'
            if j + 1 <= line_length - 1 and (_char_at(text, j + 1) == 39 or _char_at(text, j + 1) == 34):
                in_string_name = true
                # `+&""`-style chains: a binary '&' pair keeps this one an operator.
                if prev_is_binary_op and j >= 2 and _char_at(text, j - 1) == 38 and _char_at(text, j - 2) != 38:
                    in_string_name = false
                    is_binary_op = true
            else:
                is_binary_op = true
        elif in_region != -1 or is_a_symbol:
            in_string_name = false

        # '^^' has no special meaning; the LAST caret still takes the NodePath color.
        if not in_node_path and in_region == -1 and cj == 94 and not is_binary_op and (j == 0 or (j > 0 and _char_at(text, j - 1) != 94) or prev_is_binary_op):  # '^'
            in_node_path = true
        elif in_region != -1 or is_a_symbol:
            in_node_path = false

        if not in_node_ref and in_region == -1 and (cj == 36 or (cj == 37 and not is_binary_op)):  # '$' '%'
            in_node_ref = true
        elif in_region != -1 or (is_a_symbol and cj != 47 and cj != 37) or (is_a_digit and j > 0 and (_char_at(text, j - 1) == 36 or _char_at(text, j - 1) == 47 or _char_at(text, j - 1) == 37)):
            # NodeRefs cannot start with a digit: flag wrong syntax by dropping the state.
            in_node_ref = false

        if not in_annotation and in_region == -1 and cj == 64:  # '@'
            in_annotation = true
        elif in_region != -1 or is_a_symbol:
            in_annotation = false

        # --- Final color precedence --------------------------------------------------------
        var in_raw_string_prefix: bool = in_region == -1 and cj == 114 and j + 1 < line_length and (_char_at(text, j + 1) == 34 or _char_at(text, j + 1) == 39)

        if in_raw_string_prefix:
            color = _string_color
        elif in_node_ref:
            next_type = T_NODE_REF
            color = _node_ref_color
        elif in_annotation:
            next_type = T_ANNOTATION
            color = _annotation_color
        elif in_string_name:
            next_type = T_STRING_NAME
            color = _string_name_color
        elif in_node_path:
            next_type = T_NODE_PATH
            color = _node_path_color
        elif in_keyword:
            next_type = T_KEYWORD
            color = keyword_color
        elif in_signal_declaration:
            next_type = T_SIGNAL
            color = _member_variable_color
        elif in_function_name:
            next_type = T_FUNCTION
            if not in_lambda and in_function_declaration:
                color = _function_definition_color
            else:
                color = _function_color
        elif in_number:
            next_type = T_NUMBER
            color = _number_color
        elif is_a_symbol:
            next_type = T_SYMBOL
            color = _symbol_color
        elif expect_type:
            next_type = T_TYPE
            color = _type_color
        elif in_member_variable:
            next_type = T_MEMBER
            color = _member_variable_color
        else:
            next_type = T_IDENTIFIER

        # Transition tracker: `prev_text` is the last non-whitespace word/symbol run, used by
        # the declaration detection above (`func`, `signal`, `var`, `for`, `const`).
        if next_type != current_type:
            if current_type == T_NONE:
                current_type = next_type
            else:
                prev_type = current_type
                current_type = next_type
                if prev_type == T_REGION:
                    prev_text = ""
                    prev_column = j
                else:
                    var segment := text.substr(prev_column, j - prev_column).strip_edges()
                    prev_column = j
                    if not segment.is_empty():
                        prev_text = segment

        prev_is_char = is_char
        prev_is_digit = is_a_digit
        prev_is_binary_op = is_binary_op

        if color != prev_color:
            prev_color = color
            color_map[j] = {"color": color}
        j += 1
    return color_map


## Recovers the edited script's OWN top-level members (func/var/const/signal names, enum names
## and keys) plus its `extends` base type from a source text — the bootstrap GdccScript
## member-list virtuals answer empty. Returns the base type name ("" when absent or
## unresolvable, e.g. a quoted path). The walker tracks string/comment state so commented-out
## or string-embedded declarations never leak in. Column-0 anchoring matches the native
## top-level scope (an indented declaration is a local or an inner-class member).
func _scan_script_source(text: String) -> String:
    var base_type := ""
    var quote := 0  # 0 = code; otherwise the open string's delimiter code point
    var triple := false
    var at_line_start := true  # no character seen yet on this line
    var enum_brackets := 0  # >0 inside a top-level enum body; the '{' nesting depth
    var enum_expect_key := false
    var i := 0
    var text_length := text.length()
    while i < text_length:
        var c := _char_at(text, i)
        if quote != 0:
            # Backslash consumes the next char in EVERY string kind: even in raw strings the
            # tokenizer consumes `\"` and `\\` as content (4.5 gdscript_tokenizer.cpp
            # string()), so an unconditional skip matches end-key semantics everywhere.
            if c == 92:
                i += 2
                continue
            if triple and c == quote and _char_at(text, i + 1) == quote and _char_at(text, i + 2) == quote:
                quote = 0
                triple = false
                i += 3
                continue
            if not triple and c == quote:
                quote = 0
            # A string-internal newline makes the CURRENT line a continuation, so whatever
            # code follows the closing delimiter is never a column-0 declaration.
            if c == 10:
                at_line_start = false
            i += 1
            continue
        if c == 10:
            at_line_start = true
            i += 1
            continue
        # The column-0 anchor is consumed by ANY line character, including indentation: an
        # indented line can never hold a top-level declaration.
        var is_first_char := at_line_start
        at_line_start = false
        if c == 32 or c == 9:
            i += 1
            continue
        if c == 35:  # '#'
            while i < text_length and _char_at(text, i) != 10:
                i += 1
            continue
        if c == 34 or c == 39:
            quote = c
            triple = _char_at(text, i + 1) == c and _char_at(text, i + 2) == c
            i += 3 if triple else 1
            continue

        if enum_brackets > 0:
            if c == 123 or c == 91 or c == 40:  # { [ (
                enum_brackets += 1
            elif c == 125 or c == 93 or c == 41:  # } ] )
                enum_brackets -= 1
                if enum_brackets <= 0:
                    enum_brackets = 0
                    enum_expect_key = false
            elif enum_expect_key and _is_identifier_start(c):
                var key_end := i
                while key_end < text_length and _is_identifier_continue(_char_at(text, key_end)):
                    key_end += 1
                _member_keywords[text.substr(i, key_end - i)] = _member_variable_color
                enum_expect_key = false
                i = key_end
                continue
            elif c == 44 and enum_brackets == 1:  # ','
                enum_expect_key = true
            i += 1
            continue

        # Column-0 declarations only; annotations prefixing them are skipped first.
        if is_first_char:
            var decl := i
            while _char_at(text, decl) == 64:  # '@'
                decl = _skip_annotation(text, decl)
            var after_ws := _skip_horizontal_ws(text, decl)
            var keyword := _read_word(text, after_ws)
            var name_at := -1
            if keyword == "static":
                var after_static := _skip_horizontal_ws(text, after_ws + keyword.length())
                if _read_word(text, after_static) == "func":
                    name_at = _skip_horizontal_ws(text, after_static + 4)
            elif keyword == "func" or keyword == "var" or keyword == "const" or keyword == "signal":
                name_at = _skip_horizontal_ws(text, after_ws + keyword.length())
            elif keyword == "extends":
                if base_type == "":
                    base_type = _read_word(text, _skip_horizontal_ws(text, after_ws + 7))
            elif keyword == "class_name":
                # Combined form: `class_name X extends Y` — the base follows the name.
                if base_type == "":
                    var name_end := _skip_horizontal_ws(text, after_ws + 10)
                    var declared := _read_word(text, name_end)
                    if declared != "":
                        var tail := _skip_horizontal_ws(text, name_end + declared.length())
                        if _read_word(text, tail) == "extends":
                            base_type = _read_word(text, _skip_horizontal_ws(text, tail + 7))
            elif keyword == "enum":
                var enum_tail := _skip_horizontal_ws(text, after_ws + 4)
                var enum_name := _read_word(text, enum_tail)
                if enum_name != "":
                    _member_keywords[enum_name] = _member_variable_color
                    enum_tail = _skip_horizontal_ws(text, enum_tail + enum_name.length())
                if _char_at(text, enum_tail) == 123:  # '{'
                    enum_brackets = 1
                    enum_expect_key = true
                    i = enum_tail + 1
                    continue
            if name_at >= 0:
                var member_word := _read_word(text, name_at)
                if member_word != "":
                    _member_keywords[member_word] = _member_variable_color
        i += 1
    return base_type


## Skips one `@annotation` (with optional balanced, string-aware argument list) starting at
## `from` (the '@'); returns the index of the next unscanned character.
func _skip_annotation(text: String, from: int) -> int:
    var i := from + 1
    while i < text.length() and _is_identifier_continue(_char_at(text, i)):
        i += 1
    i = _skip_horizontal_ws(text, i)
    if _char_at(text, i) != 40:  # '('
        return i
    var depth := 0
    var annotation_quote := 0
    while i < text.length():
        var c := _char_at(text, i)
        if annotation_quote != 0:
            if c == 92:
                i += 2
                continue
            if c == annotation_quote:
                annotation_quote = 0
            i += 1
            continue
        if c == 34 or c == 39:
            annotation_quote = c
        elif c == 40:
            depth += 1
        elif c == 41:
            depth -= 1
            if depth <= 0:
                i += 1
                break
        i += 1
    return i


## First index >= `from` not on a space/tab.
func _skip_horizontal_ws(text: String, from: int) -> int:
    var i := from
    while i < text.length() and (_char_at(text, i) == 32 or _char_at(text, i) == 9):
        i += 1
    return i


## Identifier starting at `from`, or "" when none. Newlines and all symbols end the word.
func _read_word(text: String, from: int) -> String:
    if not _is_identifier_start(_char_at(text, from)):
        return ""
    var end := from
    while end < text.length() and _is_identifier_continue(_char_at(text, end)):
        end += 1
    return text.substr(from, end - from)


## Bounds-guarded code-point read. The ported C++ indexes freely and relies on out-of-range
## reads yielding NUL, so out-of-range here answers 0 — not a symbol, digit, or whitespace,
## hence inert in every classification branch.
func _char_at(text: String, index: int) -> int:
    if index < 0 or index >= text.length():
        return 0
    return text.unicode_at(index)


## char_utils.h `is_symbol`: ASCII punctuation plus space/tab, `_` excluded.
func _is_symbol(c: int) -> bool:
    return c != 95 and ((c >= 33 and c <= 47) or (c >= 58 and c <= 64) \
            or (c >= 91 and c <= 96) or (c >= 123 and c <= 126) or c == 9 or c == 32)


func _is_digit(c: int) -> bool:
    return c >= 48 and c <= 57


func _is_hex_digit(c: int) -> bool:
    return _is_digit(c) or (c >= 97 and c <= 102) or (c >= 65 and c <= 70)


func _is_binary_digit(c: int) -> bool:
    return c == 48 or c == 49


## The native tokenizer's unicode tables are approximated: every non-ASCII code point counts
## as an identifier char (direction-safe for highlighting).
func _is_identifier_start(c: int) -> bool:
    return (c >= 97 and c <= 122) or (c >= 65 and c <= 90) or c == 95 or c >= 128


func _is_identifier_continue(c: int) -> bool:
    return _is_identifier_start(c) or _is_digit(c)


## Space/tab only — the ported checks only ever look for horizontal spacing.
func _is_whitespace(c: int) -> bool:
    return c == 32 or c == 9


## Keywords that denote values: they do NOT block the binary-operator heuristic.
func _is_value_keyword(word: String) -> bool:
    return word == "true" or word == "false" or word == "null" or word == "PI" \
            or word == "TAU" or word == "INF" or word == "NAN" or word == "self" \
            or word == "super"


## First whitespace-delimited word of an already-stripped line (for the `#region` gate).
func _first_word(stripped: String) -> String:
    var end := 0
    while end < stripped.length() and not _is_whitespace(stripped.unicode_at(end)):
        end += 1
    return stripped.substr(0, end)
