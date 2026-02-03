# GdExtension Lite Library

## Naming conventions

1. Every type and function binding from Godot have the prefix `godot_`
2. Every GDExtension Lite function have the prefix `gdextension_lite_`
3. Constructors have the format `godot_new_<type name>` or `godot_new_<type name>_with_<arg1 type>_<arg2 type>...`
4. Destructors have the format `godot_<type name>_destroy`
5. Member getters have the format `godot_<type name>_get_<member name>`
6. Member setters have the format `godot_<type name>_set_<member name>`
7. Indexed getters have the format `godot_<type name>_indexed_get`
8. Indexed setters have the format `godot_<type name>_indexed_set`
9. Keyed getters have the format `godot_<type name>_keyed_get`
10. Keyed setters have the format `godot_<type name>_keyed_set`
11. Operators have the format `godot_<type name>_op_<operator name>` for unary operators and `godot_<type name>_op_<operator name>_<right-hand side type>` for binary operators
12. Methods have the format `godot_<type name>_<method name>`
13. Enumerators defined by classes have the format `godot_<type name>_<enum name>`
14. Godot utility functions have the format `godot_<function name>`
15. Variadic methods and utility functions expect argv/argc parameters
16. Singleton getters have the format `godot_<type name>_singleton`