class_name StringLiteralEscapeUnicodeSmoke
extends Node

func escaped_payload() -> String:
    var payload: String = "\"line\""
    payload += "\n中🙂\t尾巴\\"
    return payload

func unicode_escape_payload() -> String:
    var payload: String = "\u4F60"
    payload += "\u597D"
    payload += "-"
    payload += "\U0001F642"
    return payload

func escaped_name_payload() -> StringName:
    return &"组件🙂"

func escaped_name_matches() -> bool:
    return &"组件🙂" == &"组件🙂"

func classify_escape(flag: bool) -> String:
    var payload: String = ""
    if flag:
        payload += "alpha"
        payload += "\"beta\""
        return payload
    payload += "line"
    payload += "\nbeta"
    return payload
