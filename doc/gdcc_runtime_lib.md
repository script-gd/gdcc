# GDCC Runtime Library

This document is the current fact source for the C backend runtime library shipped under
`src/main/c/codegen/include_451`. It replaces the historical `gdextension-lite` naming note:
GDCC no longer vendors or compiles `gdextension-lite`; generated projects compile the current
module `entry.c` plus `godot/godot_binding.c`, and include the `godot/**` and `gdcc/**` helper
trees extracted by `CProjectBuilder`.

## Runtime Layout

### `godot/**`

The `godot` subtree is generated, versioned Godot 4.5.1 binding support. Files in this subtree
use public `godot_*` wrapper names, but the implementation is owned by GDCC.

- `gdextension/gdextension_interface.h`: the upstream GDExtension C ABI header used as the
  interface typedef source.
- `godot_macros.h`: shared export, visibility and static-assert macros.
- `godot_global_enums.h` and `godot_global_constants.h`: generated global enum and constant
  declarations from `extension_api.json`.
- `godot_builtin_sizes.h`, `godot_builtin_layout.h` and `godot_builtin_types.h`: generated
  built-in type size, member-offset and public C type declarations for the supported `float_64`
  ABI.
- `godot_native_structures.h`: generated native structure declarations required by the ABI.
- `godot_abi.h`: the ABI aggregation header. It includes the files above and publishes opaque
  `godot_<EngineClass>` typedefs for engine object pointers.
- `godot_interface.h` / `godot_interface.c`: generated wrappers around Godot interface functions.
  `godot_initialize_interface(...)` eagerly resolves the shared interface pointer table through
  Godot's `get_proc_address` callback before any runtime wrapper is used.
- `godot_builtin.h` / `godot_builtin.c`: generated built-in wrappers. This includes Variant
  conversion helpers, built-in constructors/destructors, member accessors, methods, operators,
  indexed accessors, keyed accessors and String codec conveniences.
- `godot_utility.h` / `godot_utility.c`: generated wrappers for Godot global utility functions.
- `godot_fixed_binding.h` / `godot_fixed_binding.c`: generated fixed wrappers that GDCC needs
  even though they are not emitted by the generic built-in or utility generators.
- `godot_binding.h` / `godot_binding.c`: the public runtime aggregation pair. The header includes
  all generated Godot binding headers; the source includes the corresponding generated `.c` files
  and is the single runtime `.c` file added to native compiler inputs.

### `gdcc/**`

The `gdcc` subtree contains handwritten backend helpers that sit above the generated Godot binding
support. These files may call `godot_*` wrappers, but their public GDCC-owned helpers should keep a
`gdcc_*`, `GDCC_*`, `GD_*`, `_GD*`, `likely` or similarly local name unless they intentionally
extend the runtime-provided `godot_*` surface.

- `gdcc_likely.h`: portable `likely(...)` / `unlikely(...)` branch prediction macros.
- `gdcc_string.h`: static `godot_String` registry and `GD_STATIC_S(...)` helper used by generated
  registration code.
- `gdcc_string_name.h`: static `godot_StringName` registry plus `GD_STATIC_SN(...)` and
  `GD_STATIC_SN_HASH(...)` helpers.
- `gdcc_bind.h`: property metadata helpers and the `GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(...)`
  macro used by generated exact-engine method-bind accessors.
- `gdcc_call.h`: convenience Variant packers and `GD_OBJECT_CALL*` helpers for dynamic object calls.
- `gdcc_operator.h`: backend-owned operator support such as division/shift guards, integer power
  and object identity comparison.
- `gdcc_intrinsic.h`: wrapper-only inbound materialization helpers for accepted Variant payloads whose
  runtime type differs from the published method metadata. It owns vector narrow-payload helpers
  and string-family `String` / `StringName` cross-case helpers; scalar `int -> float` inbound
  materialization remains directly emitted by the generated wrapper code.
- `gdcc_helper.h`: the aggregate helper header included by generated entry code. It provides
  runtime error printing, Object property get/set helpers, RefCounted ownership helpers, GDCC
  wrapper pointer conversion helpers, compatibility constructors, UTF-8 formatting helpers,
  Variant type guards, Variant writeback classification and `godot_Variant_call(...)`.

## Binding Generator Overview

`gd.script.gdcc.backend.c.gen.binding.GodotBindingTool` owns the versioned runtime generation flow.
The currently supported version is Godot `4.5.1`.

- `generate-abi-support` reads `extension_api.json` and writes the ABI declaration headers:
  macros, global enums/constants, built-in sizes/layout/types, native structures and `godot_abi.h`.
- `generate-interface` parses `gdextension_interface.h`. The lookup name comes from the `@name`
  comment, and the adjacent function-pointer typedef supplies the C signature. The generator
  validates lookup-wrapper uniqueness, writes the shared pointer table, and emits inline wrappers
  with names derived from the Godot interface lookup name.
- `generate-binding` writes the `godot_binding.h` / `godot_binding.c` aggregation pair.
- `generate-builtin` reads Godot built-in metadata and emits the built-in wrapper family.
  Constructors are sorted by Godot constructor index; member getter/setter methods that would
  duplicate generated member accessors are filtered; operator generation is limited to supported
  Variant enum surfaces; method lookup uses primary and compatibility hashes.
- `generate-utility` emits global utility wrappers, including vararg utility functions with the
  trailing `const godot_Variant **argv, godot_int argc` convention.
- `generate-fixed` emits the explicit fixed binding set described below.
- `check-fixed` scans handwritten GDCC helpers and templates for `godot_*` references that are not
  local functions and not in the provided runtime symbol set. This keeps handwritten helper calls
  from depending on wrappers that the runtime does not actually ship.
- `dump-fixed-manifest` writes a review snapshot for the fixed binding symbol list. The snapshot is
  not a source of generated behavior.

`GodotBindingSymbol` is the structural identity for generated wrapper symbols. Hashes are lookup
metadata, not symbol identity. A C function-name collision is only valid when the full ABI
signature is identical.

Module-level usage collection, exact engine method-bind helpers, engine constructor wrappers, and
module-local wrapper rendering are maintained in
`doc/module_impl/backend/godot_binding_implementation.md`.

## C Naming Conventions

Generated Godot binding wrappers keep the established `godot_*` public C shape:

1. Engine object opaque types use `godot_<ClassName>`.
2. Built-in wrapper types use `godot_<BuiltinName>` except primitives:
   `bool -> godot_bool`, `int -> godot_int`, `float -> godot_float`.
3. Constructors use `godot_new_<Type>` for the no-arg constructor and
   `godot_new_<Type>_with_<ArgType>_<ArgType>...` for typed constructors.
4. Destructors use `godot_<Type>_destroy`.
5. Member accessors use `godot_<Type>_get_<member>` and `godot_<Type>_set_<member>`.
6. Indexed and keyed built-in accessors use `godot_<Type>_indexed_get`,
   `godot_<Type>_indexed_set`, `godot_<Type>_keyed_get` and `godot_<Type>_keyed_set`.
7. Operators use `godot_<Type>_op_<operator>` for unary operators and
   `godot_<Type>_op_<operator>_<RightType>` for binary operators.
8. Built-in methods use `godot_<Type>_<method>`.
9. Variant conversion helpers use `godot_new_<Type>_with_Variant` and
   `godot_new_Variant_with_<Type>`.
10. Utility functions use `godot_<function>`.
11. Singleton getters use `godot_<Class>_singleton`.
12. Class constants exposed as functions use `godot_<Class>_<CONSTANT>`.

Backend-owned exact engine helpers are not public Godot wrappers. They use the `gdcc_engine_call*`
and `gdcc_engine_method_bind*` namespaces and are emitted into `engine_method_binds.h` only when
the module uses the corresponding exact call.

## Fixed Bindings

Fixed bindings are wrappers that are always part of the runtime-provided symbol set for the target
Godot version. They exist for backend infrastructure that needs a stable small surface outside the
generic built-in and utility generators.

For Godot `4.5.1`, `Godot451FixedBindings` currently generates these categories:

- singleton getters:
  - `godot_Engine_singleton`
  - `godot_ClassDB_singleton`
- fixed singleton/class methods:
  - `godot_Engine_is_editor_hint`
  - `godot_ClassDB_is_parent_class`
  - `godot_Object_call`
  - `godot_Object_get`
  - `godot_Object_set`
  - `godot_Object_get_instance_id`
  - `godot_Object_notification`
  - `godot_RefCounted_reference`
  - `godot_RefCounted_unreference`
  - `godot_RefCounted_init_ref`
- fixed class constants:
  - `godot_Object_NOTIFICATION_POSTINITIALIZE`
  - `godot_Object_NOTIFICATION_PREDELETE`

The source list lives in `Godot451FixedBindings.FUNCTIONS`; the C bodies are emitted from
`Godot451FixedBindings.appendDefinitions(...)` through helpers on `FixedGodotBindings.FixedRenderer`.
`FixedGodotBindings.symbols(api)` feeds both generator validation and the default
runtime-provided symbol set.

## Registering New Runtime-Provided Functions

`CBodyBuilder.callVoid(...)` and `CBodyBuilder.callAssign(...)` call
`recordUsedGodotBindingCall(funcName)` before rendering a C call. The usage buffer only enforces
names that start with `godot_`: default runtime-provided symbols are accepted; non-provided
`godot_*` calls must be explicitly registered as module-local before the call is committed.

When adding a new fixed binding:

1. Add a `FixedFunction` entry to the version-specific fixed source list, currently
   `Godot451FixedBindings.FUNCTIONS`.
2. Add the matching emitted C body in `Godot451FixedBindings.appendDefinitions(...)`.
3. Regenerate or update `godot_fixed_binding.h` and `godot_fixed_binding.c`.
4. Add or update focused tests that cover the symbol metadata and emitted C behavior.
5. Run `GodotBindingTool check-fixed` for the helper/template roots, or the equivalent targeted
   test, so handwritten `godot_*` references are still covered by the provided symbol set.

No separate usage-registration code is needed for fixed bindings after `FixedGodotBindings.symbols(api)`
contains the new function: `GodotBindingProvidedSymbols` includes that symbol list in the default
runtime-provided set.

When adding a handwritten runtime helper whose C name starts with `godot_`:

1. Prefer not to use the `godot_` prefix unless the helper is intentionally part of the
   runtime-provided Godot wrapper surface. GDCC-owned helpers should normally use `gdcc_*`.
2. If it is intentionally runtime-provided, define it in the runtime headers/sources and add the C
   function name to `GodotBindingProvidedSymbols.GDCC_HELPER_C_FUNCTION_NAMES`.
3. Add coverage proving generated code can call the helper without producing a module-local wrapper
   and that `check-fixed` does not report it missing.

When adding a new module-local wrapper kind:

1. Extend `ModuleLocalGodotBinding` with the new binding family and full `GodotBindingSymbol`
   metadata.
2. Extend `engine_method_binds.h.ftl` to render that family.
3. Record the binding in the same usage buffer that renders the call. Java body generators should
   call `usageBuffer.recordModuleLocalGodotBinding(...)`; template-level emitters should go through
   `GenerateRenderFacade.recordModuleLocalGodotBinding(...)`.
4. Ensure the explicit registration happens before or alongside the generated call that reaches
   `CBodyBuilder.recordUsedGodotBindingCall(...)`. Otherwise codegen fails with:
   `Godot binding wrapper '<name>' is not runtime-provided and was not explicitly registered as module-local`.

The generated C text scanner is only a verifier. It must not be used as a discovery mechanism for
new wrappers.
