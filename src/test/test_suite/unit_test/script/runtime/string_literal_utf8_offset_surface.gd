class_name StringLiteralUtf8OffsetSmoke
extends Node

func multibyte_prefix_value() -> String:
    var prefix = "前缀🙂"
    var marker = "甲乙"
    var payload: String = "尾巴"
    payload += "\n\"段落\""
    return payload

func branch_value(flag: bool) -> String:
    var seed = "值🙂"
    var payload: String = "分支"
    if flag:
        payload += "一🙂"
        return payload
    payload += "二\t终点"
    return payload

func escaped_after_multibyte() -> String:
    # 多字节注释🙂用于锚定源码 byte offset 到 literal lexeme 的映射
    var banner = "中文🙂"
    var payload: String = "\u706B"
    payload += "\U0001F680"
    return payload

func build_name() -> StringName:
    var head = "路径🙂"
    return &"路径/节点🙂"
