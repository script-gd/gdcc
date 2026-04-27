# GdExtension Lite Library

This document describes the Godot / GDExtension Lite binding layer. Project-owned GDCC helpers with `gdcc_*` or `gdcc_new_*` prefixes are generated backend helpers, not gdextension-lite bindings.

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

## GDCC helper boundary

The generated backend may call GDCC-owned helpers next to gdextension-lite bindings. These helpers are intentionally outside the `godot_*` naming rules above:

- `gdcc_object_to_godot_object_ptr(obj, Class_object_ptr)` converts a GDCC wrapper pointer to the backing Godot object pointer through the generated class helper.
- `gdcc_new_Variant_with_gdcc_Object(obj)` packs a raw GDCC wrapper pointer into a `Variant` and performs the object-pointer conversion internally.
- `gdcc_ref_counted_init_raw(obj, initialize)` handles explicit generated-code initialization for freshly constructed GDCC `RefCounted` wrappers.

The generated `entry.c` initializes the gdextension-lite binding table by calling `gdextension_lite_initialize(...)` from the GDExtension entry point.
