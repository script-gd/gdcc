# C Backend

## Reminders

### Use GDCC Class Types

- GDCC types cannot be used directly as a `godot_Object*` or `GDExtensionObjectPtr`, they need to be converted first.
  - Convert into Godot object pointer using `godot_object_from_gdcc_object_ptr(gdcc_object)`.
  - Convert from Godot object pointer using `gdcc_object_from_godot_object_ptr(GDExtensionObjectPtr ptr)`.
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
- Always remember GDExtension API does not receive GDCC object ptrs, convert them to `godot_Object*` using `godot_object_from_gdcc_object_ptr(gdcc_object)` first.
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
- When assigning a value to a variable, it implies that we destroy the old value in the variable and replace it with the new value. 
  If they are `Object`, that means we have to release the ownership of the old value and obtain the ownership of the new value properly, 
  otherwise it will cause memory leak or premature destruction. 
  So always remember to call `try_release_object` on the old value and `try_own_object` on the new value if they are Objects, and use non-try version if you are 100% sure they are ref-counted (no operation if 100% sure they are not ref-counted).

### Lifecycle

- `gdcc_object_from_godot_object_ptr` does not own the object, you still need to call `try_own_object` or `own_object` to retain the object if you want to keep it.
- When construct a `Variant` from an object, the new `Variant` owns the object, so you do not need to call `try_own_object` or `own_object` again.
- `try_own_object`, `try_release_object` are safe to use on non-ref-counted objects, they will do nothing in that case, but always use non-try version if you are 100% sure the object is ref-counted for better performance.
- `try_own_object`, `try_release_object`, `own_object` and `release_object` receives only Godot object ptr but not GDCC object ptr, so remember to pass `godot_object_from_gdcc_object_ptr(gdcc_object)` instead of `gdcc_object`.
- `try_destroy_object` is used to destroy an object that we own, if an object is ref-counted it is the same as `try_release_object`, if it is not ref-counted, it will be actually destroyed, so always remember to check the type and use it properly.
- Call lifecycle functions on `NULL` is safe, they will do nothing in that case, so you do not need to check if the pointer is `NULL` before calling lifecycle functions.

### Slot Write Consolidation

- Keep object and non-object slot writes separated by semantics: 
  - Objects follow ownership rules (`release old -> assign(convert ptr if needed) -> own only for BORROWED`).
  - Non-objects follow value-lifecycle rules (`prepare/copy rhs -> destroy old (if needed) -> assign`).
- For non-object writes, prefer a single Builder helper (for example `emitNonObjectSlotWrite`) used by both `assignVar` and `callAssign` result assignment paths.
- The consolidation above is a maintenance refactor only:
  - Do not change copy/destroy behavior.
  - Do not implicitly introduce copy-elision in this refactor.
  - Preserve `__prepare__` first-write behavior (no old-value destroy).
- Risk-guard conventions for this refactor:
  - `emitNonObjectSlotWrite` must not manage `markTargetInitialized` (target state stays in callers).
  - `emitNonObjectSlotWrite` must not declare/destroy temps (temp lifecycle stays in callers).
  - Keep non-object `_return_val` write in `returnValue` as direct assignment to avoid coupling return-slot flow with assign/callAssign target-state hooks.

### Temporary Variables (CBodyBuilder)

- `TempVar` carries its own mutable initialization state.
- There are two declaration forms:
  - Declaration only (`T tmp;`) for out-parameter style initialization.
  - Declaration with initializer (`T tmp = ...;`) for expression materialization/copy staging.
- `assignVar` / `callAssign` must treat **uninitialized TempVar target** as first-write:
  - Skip old-value destroy/release.
  - Perform normal assignment conversion and new-value ownership handling.
  - Mark the temp as initialized after write.
- `destroyTempVar` only destroys initialized temps, then marks them uninitialized.
- The backend does **not** enforce a global read-before-init check for temps:
  - Some APIs intentionally require passing pointers to uninitialized storage for initialization.

### `__prepare__` / `__finally__` Control Flow

- The backend inserts two special basic blocks: `__prepare__` and `__finally__`.
- In `__prepare__`, all IR-declared non-ref variables are treated as uninitialized at first, and we have to init them in this block.
  - Assignments in `__prepare__` must not destroy old values.
- In non-`__finally__` blocks, `return` and `returnValue` do not emit a real `return`.
  - For non-void functions, the return value is assigned to an implicit `_return_val` variable.
  - Control flow then jumps to `__finally__`.
- Only `__finally__` emits the actual `return` statement.
- For non-void functions, `_return_val` is declared at the top of the `__prepare__` block.
  - `_return_val` does not require automatic destruction.
- Once `_return_val` is written, a goto to `__finally__` is emitted immediately, so `_return_val` is always live until the end of the function, and its value is published through return flow. 
  - What's more, `_return_val` is never written twice since `__finally__` block directly returns after reading `_return_val`.

### Default Argument Values

All possible default values in Godot 4.5.1:
```
Transform2D(1, 0, 0, 1, 0, 0), RID(), -99, "0000000000000000000000000000000000000000000000000000000000000000", Color(0, 0, 0, 0), PackedVector2Array(), 0.08, "20340101000000", "Alert!", PackedVector3Array(), 20.0, 90, ",", "20140101000000", 50, 32767, -1, 15, 16, "application/octet-stream", PackedColorArray(), 65536, PackedFloat32Array(), 2000, 65535, 4294967295, 163, 120, 0, 1, 2, 3, 1.0, Vector2i(0, 0), null, 4, 400, 5, PackedInt64Array(), 1024, 6, 5.0, true, Callable(), "", Vector2(1, 1), Array[StringName]([]), "UDP", &"Master", Array[Plane]([]), Array[RID]([]), 8192, 1000, 0.001, 0.75, Vector2(0, -1), PackedByteArray(), Vector2(0, 0), "CN=myserver,O=myorganisation,C=IT", PackedStringArray(), "*", 30, Array[Array]([]), Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0), 32, PackedInt32Array(), Color(0, 0, 0, 1), Vector3(0, 1, 0), [], {}, Rect2i(0, 0, 0, 0), Vector2i(-1, -1), Vector2i(1, 1), &"", "•", "endregion", false, "region", 0.01, Array[RDPipelineSpecializationConstant]([]), "None", 264, 100, 0.0, Color(1, 1, 1, 1), 0.1, -1.0, Vector3(0, 0, 0), "InternetGatewayDevice", 0.2, 2.0, 500, Rect2(0, 0, 0, 0), 4.0, 0.8, NodePath("")
```
- For float and int, generate C literals directly.
- For `"..."` and `&"..."`, generate `GD_STATIC_S(u8"...")` and `GD_STATIC_SN(u8"...)` respectively.
- For `null`, generate `NULL`.
- For non-object type constructor, generate a c constructor function call, see more details in `gdextension-lite.md`.
- For `$"..."`, generate NodePath constructor with utf8_chars.

### Validation Logic

- `CBodyBuilder` and `CBuiltinBuilder` is only for generating code, they are NOT responsible for validating IR correctness, so they should assume the IR is already valid and throw `InvalidInsnException` when they encounter invalid IR.
- The validation responsibility is on the instruction generators, they should validate all aspects of the IR and throw `InvalidInsnException` when the IR is invalid, so that the backend can fail fast and avoid generating invalid C code.
- For function calls, the instruction generator should validate before calling the builder.
