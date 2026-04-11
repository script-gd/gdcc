# Frontend ABI Investigation: Variant Arguments and Typed Dictionary Boundaries

## Summary

While stabilizing the Step 8 integration coverage for frontend writable-route semantics, two boundary-shape problems were confirmed:

- a source-level `Variant` parameter exported through the current GDExtension method ABI is treated as a `Nil`-typed slot by the generated call wrapper, so non-null values can be rejected before the native method body even starts running
- a source-level typed dictionary such as `Dictionary[int, PackedInt32Array]` loses its element-type metadata when it crosses the current GDExtension method/property ABI, so any end-to-end test that passes such a value through the boundary is no longer isolating writable-route semantics alone

These are real problems, but they are **orthogonal** to Step 8's main objective:

- inner runtime-gated mutating call must finish first
- continuation must thread into the outer subscript/property route
- outer read/write must observe the post-call carrier state exactly once and in source order

If the integration test keeps `Variant` parameters or typed-dictionary parameters on the external boundary, a failure can come from the boundary ABI itself instead of the writable-route implementation. That is why the final integration fixture keeps the dynamic key carrier inside the object and passes the outer container as a plain `Dictionary`.

## Current Status

As of 2026-04-11, the backend `Variant` ABI has been repaired for ordinary method/property surfaces:

- method argument metadata now publishes `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`
- generated `call_func` wrappers no longer keep the erroneous `type != NIL` exact gate for `Variant` parameters
- method return metadata now also publishes `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`
- property registration now publishes the same outward `Variant` contract, and direct property get/set integration coverage has passed against Godot runtime
- focused runtime coverage now explicitly includes both:
  - positive paths for dynamic `call()` + direct return + direct property get/set
  - a negative guard proving non-`Variant` parameters still fail as `PackedInt32Array -> int`, not `PackedInt32Array -> Nil`

The remaining open item from this investigation is:

- typed dictionary boundary fidelity

The repaired `Variant` contract is now:

- outward metadata for method args / returns / properties uses `type = NIL`
- the same outward surfaces append `PROPERTY_USAGE_NIL_IS_VARIANT`
- generated `call_func` wrappers skip the exact `NIL` gate only for `Variant` slots
- non-`Variant` slots keep their original exact runtime gate

The historical sections below are kept because they explain the original failure chain and why the writable-route fixture was initially shaped to avoid mixing ABI defects into the continuation tests.

## Issue A: `Variant` Parameters Are Exported as `Nil` Slots

### Failing source shape

The first attempted fixture used a parameterized helper like this:

```gdscript
func next_slot(keys: Variant, seed: int) -> int:
    keys.push_back(seed)
    return 0
```

The test then tried to call it from GDScript through:

```gdscript
target.call("measure_side_effect_key_route", payloads, keys, 4)
```

where `keys` was a `PackedInt32Array`.

Observed result:

- the native method body did not run
- Godot reported:
  - `Invalid type in function 'measure_side_effect_key_route (via call)' in base 'RuntimeWritableRouteRuntimeEdgeSmoke'. Cannot convert argument 3 from PackedInt32Array to Nil.`

### Cause chain

The failure is not a generic Godot limitation on `Variant` values. It is the current `gdcc` binding contract.

1. `gdcc` models the source type as `GdVariantType`.
2. The method-binding template emits argument metadata with `arg_type = GDEXTENSION_VARIANT_TYPE_NIL`, `PROPERTY_HINT_NONE`, empty hint string, and `PROPERTY_USAGE_DEFAULT`.
3. The generated `call_*` wrapper then performs an exact runtime type check against `GDEXTENSION_VARIANT_TYPE_NIL`.
4. When the caller passes a non-null value such as `PackedInt32Array`, the wrapper itself raises `GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT` with `expected = GDEXTENSION_VARIANT_TYPE_NIL`.
5. Godot's GDScript VM formats that error as `Cannot convert argument ... to Nil`.

So the observable error is the final formatting step. The real semantic mismatch already happened inside the generated wrapper.

### Direct evidence in this repository

The template that creates method argument metadata is:

- `src/main/c/codegen/template_451/entry.h.ftl`

It always emits:

```c
gdcc_make_property_full(
    arg_type,
    arg_name,
    godot_PROPERTY_HINT_NONE,
    GD_STATIC_S(u8""),
    GD_STATIC_SN(u8"..."),
    godot_PROPERTY_USAGE_DEFAULT
)
```

The generated wrapper for a `Variant` argument in the fixture is visible in:

- `tmp/test/frontend_writable_route_runtime_edges/project/entry.h`

Key parts:

```c
GDExtensionPropertyInfo args_info[] = {
    gdcc_make_property_full(
        arg0_type,
        arg0_name,
        godot_PROPERTY_HINT_NONE,
        GD_STATIC_S(u8""),
        GD_STATIC_SN(u8"Variant"),
        godot_PROPERTY_USAGE_DEFAULT
    ),
};
```

and the generated runtime check is:

```c
const GDExtensionVariantType type = godot_variant_get_type(p_args[0]);
if (type != GDEXTENSION_VARIANT_TYPE_NIL) {
    r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
    r_error->expected = GDEXTENSION_VARIANT_TYPE_NIL;
    r_error->argument = 0;
    return;
}
```

That is the concrete reason a non-null `PackedInt32Array` argument fails before `next_slot(...)` or any continuation logic executes.

Godot's final user-facing error string comes from:

- `tmp/godot-src/modules/gdscript/gdscript_vm.cpp`

which formats:

```cpp
return "Invalid type in " + p_where + ". Cannot convert argument " + ... +
       " from " + Variant::get_type_name(...) +
       " to " + Variant::get_type_name(Variant::Type(p_err.expected)) + ".";
```

### Why this is different from Godot's own `Variant` metadata

Godot itself has an explicit way to say "the metadata type is `NIL`, but semantically this is a Variant slot":

- `PROPERTY_USAGE_NIL_IS_VARIANT`

This is visible in upstream/local Godot sources such as:

- `tmp/godot-src/modules/gdscript/gdscript_utility_functions.cpp`

Godot uses:

```cpp
PropertyInfo(Variant::NIL, m_name, PROPERTY_HINT_NONE, "", PROPERTY_USAGE_NIL_IS_VARIANT)
```

and the GDScript analyzer recognizes that convention in:

- `tmp/godot-src/modules/gdscript/gdscript_analyzer.cpp`

```cpp
if (p_property.type == Variant::NIL && (p_is_arg || (p_property.usage & PROPERTY_USAGE_NIL_IS_VARIANT))) {
    // Variant
    result.kind = GDScriptParser::DataType::VARIANT;
    return result;
}
```

The current `gdcc` binding layer does **not** emit `PROPERTY_USAGE_NIL_IS_VARIANT` for extension method arguments or properties, so the boundary is not semantically equivalent to Godot's own `Variant` metadata conventions.

### Why the integration test avoids this boundary

The final fixture moved the dynamic key carrier inside the native object:

```gdscript
var keys: Variant

func reset_side_effect_keys() -> void:
    keys = PackedInt32Array()

func next_slot(seed: int) -> int:
    keys.push_back(seed)
    return 0
```

This keeps the test focused on:

- dynamic receiver mutation
- runtime gate / writeback
- continuation threading into the outer route

without depending on the currently broken external `Variant` parameter ABI.

## Issue B: Typed-Dictionary Parameters Lose Element-Type Metadata Across the ABI

### The tempting source shape

The natural reproducer shape was:

```gdscript
func measure_side_effect_key_route(
    payloads: Dictionary[int, PackedInt32Array],
    seed: int
) -> int:
    payloads[next_slot(seed)].push_back(seed)
    return payloads[0].size() * 10 + keys.size()
```

This looks attractive because it gives the frontend a precise outer container type and a precise leaf type.

However, this shape drags the test onto a different fault surface: typed dictionary ABI fidelity.

### What Godot expects for typed dictionaries

Godot does not represent a typed dictionary only as `Variant::DICTIONARY`.

In:

- `tmp/godot-src/modules/gdscript/gdscript_parser.cpp`

`DataType::to_property_info(...)` emits typed dictionary metadata through:

- `type = Variant::DICTIONARY`
- `hint = PROPERTY_HINT_DICTIONARY_TYPE`
- `hint_string = "<key_type>;<value_type>"`

That is the mechanism Godot uses to preserve dictionary element types across metadata boundaries.

Godot's GDScript VM also has a dedicated error message for typed dictionary mismatch:

- `tmp/godot-src/modules/gdscript/gdscript_vm.cpp`

```cpp
if (p_err.expected == Variant::DICTIONARY && p_argptrs[p_err.argument]->get_type() == p_err.expected) {
    return "Invalid type in " + p_where + ". The dictionary of argument ... does not have the same element type as the expected typed dictionary argument.";
}
```

So in Godot's own world, "typed dictionary" is a `Dictionary` plus typed metadata, not a plain `Dictionary` with no hint.

### What `gdcc` exports today

The current `gdcc` backend collapses typed dictionaries at the ABI boundary in three different places.

1. `CGenHelper.renderGdTypeName(...)` maps every `GdDictionaryType` to the same external name:

- `src/main/java/dev/superice/gdcc/backend/c/gen/CGenHelper.java`

```java
case GdDictionaryType _ -> "Dictionary";
```

2. The method-binding template always emits `PROPERTY_HINT_NONE`, empty hint string, and `PROPERTY_USAGE_DEFAULT` for arguments:

- `src/main/c/codegen/template_451/entry.h.ftl`

```c
gdcc_make_property_full(arg_type, arg_name,
    godot_PROPERTY_HINT_NONE,
    GD_STATIC_S(u8""),
    GD_STATIC_SN(u8"..."),
    godot_PROPERTY_USAGE_DEFAULT)
```

3. On the C side, the apparent typed dictionary type is only a macro alias:

- `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`

```c
#define godot_TypedDictionary(key, value)  godot_Dictionary
```

So the generated C signature may *look* typed, but the actual ABI still carries only plain `Dictionary` storage and plain `Dictionary` metadata unless extra hint/usage information is emitted. Today it is not.

### Why this contaminates integration coverage

Once `payloads` stays typed across the external boundary, the test is no longer isolating writable-route semantics. It now depends on all of the following at once:

- typed dictionary metadata surviving method/property registration
- typed dictionary values being accepted by external `call()` / property plumbing
- typed dictionary values being reconstructed correctly inside generated C
- nested subscript read/write on the typed dictionary carrier
- `PackedInt32Array` leaf unpack / mutate / repack / writeback
- the actual continuation and runtime-gated reverse commit logic

That stack is much wider than the requirement.

During the failing typed-dictionary-based attempt, the generated C for `measure_side_effect_key_route(...)` had this overall structure:

- copy the incoming `payloads` into a local typed dictionary using `godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant(...)`
- compute the side-effect key through the inner call
- read the leaf through generic `variant_get_indexed`
- unpack the result into `PackedInt32Array`
- mutate it with `push_back(...)`
- write it back through generic `variant_set_indexed`

That means the test was simultaneously exercising:

- typed dictionary reconstruction
- generic variant indexing on a typed dictionary carrier
- packed-array leaf unpack/writeback

When that version crashed before `Test stop.`, the crash signal could not tell us whether the defect lived in:

-  continuation threading
- typed dictionary ABI metadata loss
- typed dictionary reconstruction
- generic indexed read/write on typed carriers
- packed-array leaf writeback

That is too much ambiguity for a acceptance test.

### Existing evidence that typed-dictionary support should be tested separately

The repository already has dedicated engine coverage for typed dictionary construction in:

- `src/test/java/dev/superice/gdcc/backend/c/gen/CConstructInsnGenEngineTest.java`

That test checks that typed dictionary construction code is emitted and can be exercised independently of writable-route behavior.

So typed dictionary support is not ignored. It is simply a separate acceptance dimension and should not be fused into the continuation test fixture.

## Why the Final Fixture Uses Internal `Variant` + Plain `Dictionary`

The final accepted fixture uses:

```gdscript
var keys: Variant

func measure_side_effect_key_route(payloads: Dictionary, seed: int) -> int:
    var slot_key: Variant = next_slot(seed)
    payloads[slot_key].push_back(seed)
    var zero_key: Variant = 0
    return payloads[zero_key].size() * 10 + keys.size()
```

This shape deliberately does three things:

1. It keeps the dynamic carrier (`keys`) inside the object, so the test does not depend on the broken external `Variant` parameter ABI.
2. It uses plain `Dictionary` on the external boundary, so the test does not depend on typed-dictionary metadata fidelity.
3. It materializes `slot_key` and `zero_key` as explicit `Variant` locals, which satisfies the current frontend container typing rule for generic `Dictionary`.

That last point comes from:

- `src/main/java/dev/superice/gdcc/frontend/sema/analyzer/support/FrontendSubscriptSemanticSupport.java`

which requires the provided key type to be assignable to the container key type. For a plain `Dictionary`, the current key type is `Variant`. So:

- `payloads[next_slot(seed)]` with a raw `int` key pulls in a separate compile-surface issue
- `var slot_key: Variant = next_slot(seed)` keeps the test focused on the runtime route instead

The final generated C assertion was also narrowed to the real semantic target:

- the integration test now checks for generic `godot_variant_get(...)` / `godot_variant_set(...)`

instead of wrongly asserting that this route must use the `gdcc_variant_requires_writeback(...)` helper directly.

## Practical Conclusions

For acceptance, the integration fixture must avoid:

- external `Variant` method parameters or properties when the test needs to pass non-null values through `target.call(...)`
- external typed-dictionary parameters or fields when the test's primary goal is writable-route continuation semantics rather than typed-container ABI fidelity

Otherwise the test stops being a precise probe of:

- block threading
- source order
- per-layer reverse commit
- dynamic key subscript continuation

and starts mixing in unrelated ABI defects.

## Follow-up Direction

### `Variant` boundary

This part is no longer an open investigation item for ordinary method/property surfaces.

The stable backend touchpoints are now:

- `CGenHelper.renderBoundMetadata(...)`
- `CGenHelper.renderPropertyMetadata(...)`
- `gdcc_make_property_full(...)`
- `gdcc_bind_property_full(...)`

Any future maintenance on ordinary `Variant` ABI should reuse those touchpoints and keep the existing runtime/codegen regression coverage aligned with them.

### Typed dictionary boundary fix

To make typed dictionaries semantically survive the ABI boundary, investigate:

- emitting `PROPERTY_HINT_DICTIONARY_TYPE` and the correct `hint_string` for typed dictionary arguments/properties
- auditing whether typed array/dictionary metadata is preserved consistently for:
  - method arguments
  - return values
  - exported properties
- adding focused end-to-end tests specifically for typed dictionary parameter/property boundaries, separate from writable-route continuation coverage

Until those fixes land, integration tests should keep using:

- internal `Variant` state for dynamic carriers
- external plain `Dictionary` for the outer mutable route
- explicit `Variant` locals for generic dictionary keys

so that failures remain attributable to itself.

The typed dictionary follow-up should reuse the backend helper touchpoints established by the `Variant` ABI repair, but its tests, acceptance criteria, and risk analysis must remain independent from the `Variant` regression surface documented here.
