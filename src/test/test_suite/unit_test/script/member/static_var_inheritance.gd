class_name StaticVarInheritance
extends Node

# Inner classes: subclass access to a base static var shares the same storage; the value is
# never copied per class.
class CounterBase extends RefCounted:
    static var count: int = 0

    static func bump() -> int:
        count += 1
        return count

class CounterSub extends CounterBase:
    static func current() -> int:
        return count

func exercise() -> int:
    CounterSub.bump()
    CounterBase.bump()
    return CounterSub.current()
