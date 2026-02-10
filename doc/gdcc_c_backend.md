# C Backend

## Reminders

### Use GDCC Class Types

- GDCC types cannot be used directly as a `godot_Object*` or `GDExtensionObjectPtr`, they need to be converted first.
  - Convert into godot Object using `_object` field.
  - Convert from godot Object using `godot_object_get_gdcc_object`.
  - `Variant` can be converted to/from GDCC types using `godot_new_Variant_with_gdcc_Object` and `godot_new_gdcc_Object_with_Variant`.
- `godot_float` is usually a typedef for `double`, but it should always be used as `godot_float` for compatibility.
- Any Object, including engine object and GDCC objects, are always passed as pointers, and the pointers are passed as values, usually we do not use pointers to pointers.
- There are 3 types: built-in types, engine types, and GDCC types, see [gdcc_type_system.md](gdcc_type_system.md) for more details.
- Engine types and GDCC types are all Objects, built-in types are not Objects.
- Only `godot_bool`, `godot_int` and `godot_float` can be used directly as C primitive types, they are always passed by value.
- For `String`, `StringName`, `NodePath`, `Callable`, `Signal`, `Packed*Array`:
  - Though they are syntax passed by value, however the C implementation is a struct that hold an opaque pointer to the actual C++ class inside the engine.
  - When passing them into functions, we need to pass their pointers of the struct.
  - When returning them from functions, we need to return the struct by value.
  - When assigning them to variables or using them to call functions, we have to copy them using `godot_new_<TypeName>_with_<TypeName>(TypeName* value)`.
  - When a value of these types are no longer used, call `godot_destroy_<TypeName>(TypeName* value)` to destroy them properly.
- For `Dictionary`, `Array` and `Variant`:
  - They are passed by ref, however the C implementation is a struct that hold an opaque pointer and other stuff to the actual C++ class inside the engine.
  - Though they are ref-counted, the passing and returning conventions are the same as `String` etc.
  - We still need to pass their pointers when passing into functions, and return the struct by value when returning from functions.
  - The copy function of these actually creates a new struct pointing to the same underlying C++ object, so we still need to use `godot_new_<TypeName>_with_<TypeName>(TypeName* value)` to copy them.
  - Destroying them using `godot_destroy_<TypeName>(TypeName* value)` is also needed which will decrease the reference count, and actually destroy the underlying C++ object only when the reference count reaches zero.
- Especially for `Variant` & `Object`gdcc_object_from_godot_object_ptr the copy, construct and destroy function name use `variant` and `object` (lowercase).
- Some objects that extends `RefCounted` are reference counted, and they need to be retained and released properly.
  - Use `try_own_object` and `try_release_object` to retain and release reference counted objects.
  - When destroying a reference counted object, think twice if we need to destroy (mem-delete) or just release it (decrease reference count).
  - If we can 100% sure that an object is ref-counted, we can use `own_object` and `release_object` directly, these 2 functions are faster.
- For type mapping between compiler types and C types:
  - GDCC types are directly used as C types, e.g., `MyCustomGdClass` is used as `MyCustomGdClass*`.
  - Other types are mapped with a `godot_` prefix, e.g., `int` is mapped to `godot_int`, `String` is mapped to `godot_String`.
- Always remember GDExtension API does not receive GDCC object ptrs, convert them to `godot_Object*` using its internal proxy ptr `gdcc_object->_object` first.
- When receiving `godot_Object*` from GDExtension API that is actually a GDCC object, convert it to the correct GDCC type using `gdcc_object_from_godot_object_ptr(GDExtensionObjectPtr ptr)` if necessary.

### Implementing a New Instruction Generator

- Each instruction generator implements `CInsnGen`, for those who wants to use FreeMarker templates, they can extend `TemplateInsnGen`.
- Always remember to do full validation first. If there are error, throw a `InvalidInsnException(String func, String block, int index, String insn, String reason)`.
- `helper.context().classRegistry()` gives access to the class registry that can be used to lookup class information.
- CGenHelper has many helper functions to generate C code snippets, read all the code and use them when possible.
- When generating variable assignments in C code, always check the followings:
  - If the result variable exists and its type is compatible with the assigned value type. (`ClassRegistry#checkAssignable` helps)
    - For built-in types, types are compatible only if they are exactly the same type.
    - For engine types and GDCC types, types are compatible if the assigned value type is the same or a subclass of the result variable type.
    - Always remember if the value that is assigning into a result variable whose type is a GDCC object is returned from a GDExtension function, it is always a `godot_Object*` that needs to be converted to the correct GDCC type.