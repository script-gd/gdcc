class_name EngineOptionButtonDefaultArgsSmoke
extends Node

func item_count_after_add_separator_default_text() -> int:
    var button: OptionButton = OptionButton.new()
    button.add_separator()
    return button.get_item_count()
