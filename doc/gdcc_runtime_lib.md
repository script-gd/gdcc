# GDCC Runtime Library

This document is the current fact source for the C backend runtime library shipped under
`src/main/c/codegen/include_451`. It replaces the historical `gdextension-lite` naming note:
GDCC no longer vendors or compiles `gdextension-lite`; generated projects compile the current
module `entry.c` plus the runtime `.c` translation units `godot/godot_binding.c`,
`gdcc/minicoro.c` and `gdcc/gdcc_coroutine.c`, and include the `godot/**` and `gdcc/**`
helper trees extracted by `CProjectBuilder`.

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
  all generated Godot binding headers; the source includes the corresponding generated `.c` files.
  It is the only `godot/**` file added to native compiler inputs; the `gdcc/**` runtime `.c`
  files (`minicoro.c`, `gdcc_coroutine.c`) are added alongside it (see §Coroutine Runtime).

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
- `gdcc_intrinsic.h`: umbrella header that includes `gdcc/intrinsic/*` split implementations. It owns
  wrapper-only inbound materialization helpers (`call_arg_materialize.h`) for accepted Variant payloads
  whose runtime type differs from published method metadata, plus compiler-only for-iterator runtime
  storage helpers:
  - `intrinsic/for_range_iter.h`: `gdcc_for_range_iter` POD storage; `from_bounds` / `should_continue` /
    `next` / `get`. Zero step is empty (no diagnostic), matching Godot 4.5.1 optimized range loop.
  - `intrinsic/for_float_iter.h`: POD FLOAT shorthand (`current < end`, step `+1.0`).
  - `intrinsic/for_variant_iter.h`: generic `Variant::iter_*` protocol state.
  - `intrinsic/for_string_iter.h`: String index iteration (`substr(index, 1)`).
  - `intrinsic/for_array_iter.h`: shared Array handle + cached size; `get` uses
    `godot_array_operator_index_const(&source, index)` + Variant copy (no raw base-pointer cache;
    Array is reference-semantic and may reallocate on resize).
  - `intrinsic/for_dictionary_iter.h`: heap-shared keys box (`Dictionary_keys()` snapshot + non-atomic
    refcount) + cached contiguous key `Variant*` base; `next`/`copy` only bump refcount; last
    `destroy` frees the box. For-iter locals are single-threaded. **Semantic divergence**: Godot VM
    uses a live cursor that invalidates on mutation during iteration; this snapshot approach is a
    deliberate safer divergence — mutations to the dictionary after the snapshot do not affect the
    iteration sequence.
  - `intrinsic/for_packed_array_iter.h`: per-family Packed*Array iterator structs (no kind union).
    Each family owns a typed COW snapshot + typed element base pointer; `get` returns the typed
    element without runtime family dispatch.
  - these helpers are GDCC-owned runtime support and must keep the `gdcc_*` namespace instead of
    pretending to be generated `godot_*` wrappers
- `gdcc_helper.h`: the aggregate helper header included by generated entry code. It provides
  runtime error printing, Object property get/set helpers, RefCounted ownership helpers, GDCC
  wrapper pointer conversion helpers, compatibility constructors, UTF-8 formatting helpers,
  Variant type guards, GDScript `is` type-test helpers, Variant writeback classification and
  `godot_Variant_call(...)`. It also pulls in sibling headers such as `gdcc_callable.h` and,
  immediately after the `GDCC_PRINT_RUNTIME_ERROR` macro definition, `gdscript_builtins.h`.
- `gdscript_builtins.h`: GDScript language-level builtins (header-only, `static inline`). These
  back language constructs registered by the GDScript module rather than the GDExtension API, so
  no generated `godot_*` wrapper exists for them. Currently provides:
  - `gdcc_assert_failed(message_or_null, func, file, line)`: reports a failed user-level `assert`
    through the shared `GDCC_PRINT_RUNTIME_ERROR` channel (`NULL` message falls back to a fixed
    "Assertion failed." text; otherwise the String message is converted to UTF-8 and prefixed).
    The caller owns the default-return edge; the helper only reports.
  - `gdcc_len(value)`: Godot 4.5 `len()` semantics — dynamic Variant dispatch that unpacks a
    temporary payload copy, forwards to the matching per-type helper, and destroys the copy. The
    per-type helpers `gdcc_len_string` / `gdcc_len_string_name` / `gdcc_len_array` /
    `gdcc_len_dictionary` / `gdcc_len_packed_*_array` take the concrete payload pointer and may be
    called directly by the intrinsic channel when the argument type is statically known. Any other
    Variant type prints a runtime error and returns `0`.
  - `gdcc_char(code)`: Godot 4.5 `char()` semantics — only `code < 0 || code > UINT32_MAX` is an
    error (prints and returns an empty String); `0` yields NUL, and surrogates or values above
    U+10FFFF are delegated to `String.chr` (U+FFFD substitution) without an extra error.
  - `gdcc_ord(value)`: Godot 4.5 `ord()` semantics — requires a string of exactly one character
    (otherwise prints a runtime error and returns `0`) and returns its Unicode code point via
    `String.unicode_at(0)`.
  - `gdcc_range(argv, argc)`: Godot 4.5 `range()` semantics — builds an unparameterized Array of
    int elements; 1 argument is `[0, count)`, 2 arguments `[from, to)` step 1, 3 arguments
    `from`/`to`/`step` with direction-aware emptiness (positive step with `from >= to` or negative
    step with `from <= to` yields empty) and ceiling-division sizing. Following Godot's
    `can_convert_strict(INT)` validation, INT/BOOL/FLOAT payloads are accepted and converted;
    arity outside 1..3, any other argument type, `step == 0`, or an element count above INT32_MAX
    prints a runtime error and returns an empty Array (arity is normally gated by the frontend;
    the helper re-checks defensively). Overflow hardening beyond the engine reference: the element
    count is computed on the unsigned distance/stride and the fill loop iterates by index without
    incrementing past the final element, so INT64_MIN/INT64_MAX extremes cannot wrap the signed
    arithmetic into an infinite loop. The returned Array is OWNED by the caller.
  - `gdcc_is_instance_of_global(value, type)`: global `is_instance_of()` — v1 supports only the
    `TYPE_*` integer-enum form of `type` (compared against `value`'s Variant type; out-of-range
    enum values print a runtime error and return `false`); class/script Object type arguments
    print a runtime error and return `false` (class-value representation is not designed yet).
    Hard boundary: the `x is T`
    expression uses the separate `gdcc_is_instance_of_object_*` helper family and never this one.
  - `load`/`preload` deliberately have **no** `gdcc_*` helper: frontend lowering rewrites both to
    the `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` instruction pair, so
    the existing singleton getter binding and the exact engine method dispatch (with backend
    default-argument materialization) carry them. A `call_global "load"` reaching the backend
    fails fast as malformed IR.
- `gdcc_callable.h`: custom Callables for `construct_standalone_callable` and
  `construct_lambda`. Owns `gdcc_new_standalone_callable` (growable heap intern table of
  `gdcc_standalone_callable_spec`, one `godot_mem_alloc` per unique `(kind, owner, name)`,
  `ClassDB.class_call_static` forwarding, `gdcc_standalone_callable_registry_destroy_all()`
  on unload) and `gdcc_new_lambda_callable(userdata, object_id, call/is_valid/free/argc)`.
  Lambda `object_id` is supplied by the caller from a cached fat-pointer `instance_id`
  when the lambda captures `self`; otherwise it is `0`. The helper never recovers an ID
  from a raw object pointer. Hash/equal stay Godot's default (`call_func` + userdata
  pointer identity). The includer must declare `class_library` first.
  - Object **values** in generated code are per-type fat pointers (`gdcc_<Type>_fat_ptr` from
    module `object_fat_ptr_types.h`); `gdcc_helper.h` owns the shared raw/ID query and lifecycle
    surface used by those helpers.
  - Query helpers such as `gdcc_object_live_ptr`, `gdcc_object_is_null_raw_and_id`, and
    `gdcc_object_id_is_ref_counted` are annotated `GDCC_PURE` / `GDCC_CONST` where safe.
  - Lifecycle helpers (`own_object` / `release_object` / `try_*`) take validated live raw pointers
    (plus cached `instance_id` for `try_*`), mutate ownership / ObjectDB state, and are never pure.
  - GDScript `is` / LIR `is_instance_of` helpers (null/freed → **false**; do **not** reuse
    `gdcc_check_variant_type_object`, which accepts null for unpack):
    - `gdcc_is_instance_of_object_{raw_and_id,variant}` — Object inheritance via ClassDB.
    - `gdcc_is_instance_of_typed_{array,dictionary}[,_variant]` — exact typed-container metadata.
      Used for parameterized targets even when the static value type is bare `Array` /
      `Dictionary` (those slots may still carry typed metadata at runtime).
    - Non-parameterized builtin `is` stays inlined as `godot_variant_get_type(...) == ENUM`.
    - Freed instances produce `false` (Godot release behavior); Godot's debug-only runtime
      error is not replicated.
  - GDScript `as` / LIR `object_cast` helpers (ownership-neutral; return validated live raw or NULL):
    - `gdcc_object_cast_raw_and_id(raw, instance_id, expected_class_name)` — fat-pointer path;
      null/freed/class-mismatch → NULL. Never recovers ID from an unvalidated raw pointer.
    - `gdcc_object_cast_variant(value, expected_class_name)` — OBJECT / non-OBJECT / NIL payload;
      non-OBJECT and NIL → NULL. Success raw is passed to target `_from_raw` so instance_id is
      captured from the live object; failure writes canonical null `{ptr=NULL, instance_id=0}`.
    - Do **not** use `godot_object_cast_to`, `gdcc_check_variant_type_object`, or plain
      `_fat_ptr_from_variant` as the class-check/cast mechanism.
  - GDScript `as` / LIR `builtin_cast` does **not** add a dedicated runtime helper: generators pack
  the source once, call `godot_variant_construct` with the base Variant enum (parameterized
    `Array[T]` / `Dictionary[K, V]` use ARRAY/DICTIONARY only), check `GDExtensionCallError`, then
    exact-unpack. Construct failure prints via `GDCC_PRINT_RUNTIME_ERROR` and default-returns.
- `minicoro.h` / `minicoro.c`: vendored stackful coroutine library (upstream
  `edubart/minicoro`, dual Public Domain / MIT No Attribution license; license header comment
  is preserved in both files). See §Coroutine Runtime for the pinned version and locked
  configuration.
- `gdcc_coroutine.h` / `gdcc_coroutine.c`: GDCC-owned coroutine runtime helpers (`gdcc_coro_*`).
  See §Coroutine Runtime for the full contract. Unlike the other `gdcc/**` helpers these two
  files are not header-only: `gdcc_coroutine.c` is a real translation unit, added to native
  compiler inputs together with `minicoro.c`.

## Coroutine Runtime (minicoro + gdcc_coroutine)

This section freezes the runtime contract for `await` / stackful coroutines. The frontend/LIR
surface is documented in `gdcc_low_ir.md` §Coroutine Instructions; ownership clauses live in
`gdcc_ownership_lifecycle_spec.md` §3.10; the per-coroutine-function hidden state class naming
contract lives in `module_impl/frontend/gdcc_facing_class_name_contract.md` §1.3. Coroutine
lambdas (`await` inside a lambda body, `frontend_await_implementation.md`) required **no
runtime changes**: their capture frame fields are filled and destroyed entirely by generated
code (start thunk / `free_instance`), and the existing `gdcc_coro_state_desc` callback set
already covers them.

### Vendored minicoro

- Source: `edubart/minicoro`, single-file library, vendored as `gdcc/minicoro.h` +
  `gdcc/minicoro.c`. The `.c` file only defines the locked configuration macros and
  `MINICORO_IMPL`, then includes `minicoro.h`.
- Pinned upstream commit: `02dad0f8b7cbb12fe6e216ae7a76db15ca55cd7b` (`main`, 2026-08).
  Do not upgrade without re-verifying the backend/allocator semantics below.
- License: dual Public Domain (Unlicense) / MIT No Attribution; the upstream license header
  comment is kept verbatim in the vendored files.
- Locked compile-time configuration (defined in `minicoro.c` before the include):
  - `MCO_USE_ASM`: forces the assembly context-switch backend on every supported target.
    The fiber backend (`MCO_USE_FIBERS`) is forbidden: it converts the calling thread to a
    Windows fiber, which fails when Godot's main thread already is a fiber.
  - `MCO_USE_VMEM_ALLOCATOR`: virtual-memory backed stack allocator.
- Vmem commit semantics, verified against the pinned source:
  - POSIX: `mmap(MAP_PRIVATE | MAP_ANONYMOUS)` reserves address space only; physical pages
    materialize on demand. Reservation != commit.
  - Windows: `VirtualAlloc(MEM_RESERVE | MEM_COMMIT)` commits the full reservation up front
    (commit charge), while physical RAM pages are still demand-zero on first touch. The
    "reservation != commit" property therefore holds for physical RAM but **not** for commit
    charge on Windows.
- Real minicoro API surface used by GDCC (verified against the pinned header):
  - `mco_desc mco_desc_init(void (*func)(mco_coro *), size_t stack_size)`; `desc.user_data`
    carries the coroutine frame (state object wrapper) pointer.
  - `mco_result mco_create(mco_coro **out, mco_desc *desc)`: allocates + initializes only;
    the coroutine starts at `MCO_SUSPENDED`. Failure (`!= MCO_SUCCESS`, e.g.
    `MCO_OUT_OF_MEMORY`) is treated as OOM under the single-channel ABI: the start thunk
    reports a runtime error, writes the declared-type default into the typed return slot
    (void: nil), runs `pack_result`, sets `done = true`, and returns the OWNED `done` state
    object with `co == NULL` (`mco_resume` never happened). `free_instance` accepts
    `co == NULL`: it skips `mco_destroy` but still destroys `result_cache`, the typed
    return slot and the parameter fields.
  - `mco_result mco_resume(mco_coro *co)` / `mco_result mco_yield(mco_coro *co)`.
  - `mco_state mco_status(mco_coro *co)`: `MCO_SUSPENDED` (not started or yielded),
    `MCO_RUNNING`, `MCO_NORMAL` (active but resumed another coroutine), `MCO_DEAD`
    (finished). Only `MCO_SUSPENDED` may be resumed; `MCO_DEAD` ends the lifecycle.
  - `mco_result mco_destroy(mco_coro *co)`: only valid on `MCO_DEAD`/`MCO_SUSPENDED`
    coroutines; GDCC only destroys `MCO_DEAD` ones.
  - `void *mco_get_user_data(mco_coro *co)`: recovers the frame pointer inside the body.
- Coroutine stack size is centralized as one constant, default 1 MiB reservation (engine
  calls such as Variant/ClassDB/regex/JSON execute on the coroutine stack, so stack depth
  is not statically bounded).

### `gdcc_coroutine.h` / `gdcc_coroutine.c`

All public names keep the `gdcc_*` namespace, so these helpers are outside the `godot_*`
usage-buffer registration surface (see §Registering New Runtime-Provided Functions). Generic
logic lives here; everything per-coroutine-function (state class wrapper, registration,
typed-slot callbacks) is generated by the backend templates.

- `gdcc_coro_state_header`: the common frame header embedded in every hidden state class
  wrapper (the wrapper's first field is `_object`; the header follows it, never at offset 0):
  - `magic` constant (`GDCC_CORO_STATE_MAGIC`) for `identify` validation.
  - `desc`: pointer to the per-class `gdcc_coro_state_desc`.
  - `obj`: `GDExtensionObjectPtr` back-pointer to the state object, filled at creation.
  - `co`: `mco_coro *` (NULL until the entry thunk creates the coroutine).
  - `done` / `cancel` flags.
  - `result_cache`: `godot_Variant`, always in constructed state from `POSTINITIALIZE`
    (zero-initialized storage is a nil Variant); `free_instance` destroys it
    unconditionally. Because the storage is always constructed, every write into it must
    follow the destroy-then-copy discipline: `godot_variant_destroy(&result_cache)` first,
    then `godot_variant_new_copy(&result_cache, ...)`; never `new_copy` into constructed
    storage, never `memset` over a live Variant.
  - `waiters`: head of the waiter list (`gdcc_coro_waiter`).
- `gdcc_coro_state_desc`: per-class descriptor carrying generated callbacks, so the generic
  TU never touches typed frame fields:
  - `pack_result(state)`: finalize step 1; copies the typed return slot into `result_cache`
    (destroy-then-copy discipline above). The slot is left intact — copy, not move — so the
    typed resume channel and the connect-after-done fast path keep working after `done`.
    Void coroutines pack nil.
  - `copy_ret_slot(state, out_typed)`: the typed resume channel; copies the callee's
    declared return type out of the frame into a waiter's `out_typed`. Called by
    `gdcc_coro_await_state`'s done fast path and by `gdcc_coro_finalize` for each typed
    waiter. Never entered on the cancel path (cancel never finalizes). `out_typed` is an
    **already-initialized** destination slot (LIR locals are initialized in `__prepare__`),
    so the callback must follow the ordinary overwrite/slot-write discipline: destroyable
    types prepare the new copy first, then destroy the old value and write; Object types
    retain the new value and release the old one; a `void` specialization destroys the old
    Variant and leaves a constructed nil; primitives use plain assignment.
  - `destroy_ret_slot(state)`: destroys the typed return slot content in place; called
    exactly once per state object, by the generated `free_instance` code, on completion and
    cancel paths alike. Must tolerate a never-written (zero-initialized) or moved-from slot.
  - `emit_completed(state)`: finalize step 4; emits the `completed(result_cache)` signal on
    `state->obj`.
  - Beside the descriptor, each state class also generates a `<state>__move_result`
    accessor used solely by the ClassDB wrapper's synchronous-completion fast path: it
    moves the typed return slot out (leaving a valid moved-from slot) so the wrapper never
    casts to the concrete wrapper type itself.
- `gdcc_coro_state_header_init(state, desc, obj)`: common-header initialization for the
  generated `POSTINITIALIZE` path — zeroes the header (zeroed Variant storage is a
  constructed nil Variant), then sets `magic` / `desc` / `obj`.
- `gdcc_coro_state_free(state)`: the generic half of the state class `free_instance` —
  reports a runtime error if the coroutine is neither `MCO_DEAD` nor never-created
  (`co == NULL` on the OOM path), skips `mco_destroy` when `co == NULL`, otherwise
  `mco_destroy`, destroys the always-constructed `result_cache`, and revokes `magic`. The
  generated `free_instance` half calls `desc->destroy_ret_slot` **exactly once**, then
  destroys the typed parameter fields and frees the wrapper.
- `gdcc_coro_state_slot_init()` / `gdcc_coro_state_slot_destroy(slot)`: slot helpers for
  compiler-local `compiler::GdccCoroState` variables (`godot_Object *` storage). Init is the
  nullary call-and-assign shape (`$slot = gdcc_coro_state_slot_init();`) returning `NULL`;
  destroy takes the slot address (`godot_Object **`), releases the OWNED state reference via
  the RefCounted release primitive (driving the PREDELETE cancel path when it was the last
  reference) and resets the slot to `NULL`. Destroy tolerates a NULL slot (moved-from after
  `await`, or never written).
- Dedicated instance-binding token: `gdcc_coro_binding_token()` returns the address of a
  `gdcc_coroutine.c`-internal global. Hidden state objects use this token for their **only**
  instance binding, with the common header pointer as payload and all callbacks `NULL`.
  Godot 4.5.1's `set_instance_binding` owns slot zero and cannot append a second binding;
  `get_instance_binding` invokes `create_callback` while holding a non-recursive mutex, so a
  callback must not recursively query another binding. The token is distinct per loaded
  gdcc module, so a foreign module's state objects fall through to the external-object
  duck-type path. `object_set_instance(..., wrapper)` remains the independent source of the
  `free_instance` / notification callback pointer.
- `gdcc_coro_state_identify(GDExtensionObjectPtr obj)`: O(1) pure-C check —
  `godot_object_get_instance_binding(obj, gdcc_coro_binding_token(), NULL)` plus magic
  validation; returns the header pointer or `NULL` for any non-state object.
- `gdcc_coro_await_signal(godot_Signal *sig, godot_Variant *out, mco_coro *co,
  gdcc_coro_state_header *self)`: connects `sig` one-shot (`CONNECT_ONE_SHOT == 4`) with a
  custom Callable whose userdata carries `{co, out}` and holds a strong reference to the
  awaiter's own state object (the final keep-alive edge of a suspended coroutine; released
  in the Callable `free_func`), then `mco_yield(co)`. The Callable callback writes `*out`
  per the resume-value rule (0 args → nil, 1 arg → the argument, N args → `Array` of all
  arguments; callback arguments are only ever copied, never consumed) and then
  `mco_resume(co)`. Connect failure is a deliberate deviation from Godot's
  hang-forever-after-failed-connect: report a runtime error, fill `out` with nil, and
  return **without** suspending.
- `gdcc_coro_await_state(gdcc_coro_state_header *callee, void *out_typed, mco_coro *co,
  gdcc_coro_state_header *self)`: static coroutine-call path. If `callee->done`,
  `desc->copy_ret_slot(callee, out_typed)` copies the typed result out and returns
  immediately (connect-after-done fast path, no suspend). Otherwise pushes `{co, out_typed,
  strong ref to self->obj}` onto `callee->waiters` (typed-waiter kind; callee → awaiter
  keep-alive edge) and yields; on resume, `out_typed` has already been filled by
  `gdcc_coro_finalize` through the same callback. **Consume contract**: the call always
  consumes the call site's OWNED reference to the callee state object — on the registered
  path it is released *before* `mco_yield`, so the awaiter never holds the callee across a
  suspension and the keep-alive graph stays a DAG (callee → awaiter only); the callee stays
  alive through its own wait edges. The caller must not touch its callee reference after
  this call.
- `gdcc_coro_await_dynamic(godot_Variant *operand, godot_Variant *out, mco_coro *co,
  gdcc_coro_state_header *self)`: three-layer runtime dispatch:
  1. `TYPE_SIGNAL` → extract the Signal and delegate to `gdcc_coro_await_signal`.
  2. `TYPE_OBJECT` → liveness first (null payload passes through; freed object reports the
     runtime error `"Trying to await on a freed object."`, fills nil, and returns without
     suspending — aligned with Godot), then `gdcc_coro_state_identify`:
     - hit (own state object) → shared waiter-channel core (`gdcc_coro_register_waiter`,
       Variant-waiter kind): `done` fast path (copies `result_cache` into `out`) or direct
       C-level waiter registration (single-threaded check-then-register is atomic, no
       connect-after-done window). On the registered
       (suspend) path the operand's callee reference is released and `operand` is reset to
       nil **before** yielding — the awaiter frame must not hold the callee across a
       suspension (same no-cycle rule as `gdcc_coro_await_state`). On the done fast path
       `operand` is left untouched;
     - miss (external object) → build `Signal(obj, "completed")` and connect one-shot;
       the connect error code **is** the existence check: objects without a `completed`
       signal pass through (`out` = copy of the operand, no error, no suspend), while a
       pre-connect allocation failure (`godot_ERR_OUT_OF_MEMORY`) reports a runtime error,
       fills nil, and never passes through. This duck-type is deliberately wider than
       Godot's internal function-state class check and supports explicitly exposed
       `completed(result)` objects from scripts or other extensions. Godot 4 does not expose
       native GDScript coroutine state objects to script callers, so direct interpreted
       coroutine-state transfer is not a supported interop surface. Connect-after-done on an
       external completed-state object still hangs because there is no cached-result channel.
       Throughout the external-object suspension the awaiter frame keeps the `operand`
       Variant retained (Signal connections do not keep the emitter alive —
       Godot-aligned keep-alive direction).
  3. Any other type (including nil) → pass through: `out` = copy of the operand, no suspend.
- `gdcc_coro_finalize(gdcc_coro_state_header *state)`: the single completion exit, called
  only from `mco_resume` return points that observed `MCO_DEAD` (entry thunk, signal
  callback, waiter cascade). Never entered from the cancel path. Invariant order:
  1. `desc->pack_result(state)` — copies the typed return slot into `result_cache`; the
     slot is left intact (copy, not move) for the typed waiter channel and the
     connect-after-done fast path.
  2. `done = true` — `done` and `result_cache` must be visible before any resume/emit.
  3. Pop waiters one by one: fill the waiter's out — `desc->copy_ret_slot(state, out_typed)`
     for typed waiters, `godot_variant_new_copy` from `result_cache` for Variant waiters —
     then `mco_resume` the waiter, then cascade `gdcc_coro_finalize` when the resumed
     waiter returned `MCO_DEAD` (every completion-path resume return point applies this
     `MCO_DEAD` check: entry thunk, signal callback, waiter cascade), and only then release
     the waiter reference edge (releasing before the resume could drop the last keep-alive
     edge mid-flight).
  4. `desc->emit_completed(state)` last, for external listeners.
  Finalize is re-entrant (waiter resumes may cascade nested finalizes); the early `done`
  publication makes nested entry on the same state a no-op.
- `gdcc_coro_cancel(gdcc_coro_state_header *state)`: the abandonment path, mutually
  exclusive with finalize. Hooked from the state class `NOTIFICATION_PREDELETE` (never from
  `free_instance`; the state class destructor must not touch frame fields). If `done` or
  already cancelled, returns immediately. Otherwise: set `cancel = true`; when
  `mco_status(co) == MCO_SUSPENDED`, `mco_resume(co)` — the body checks the flag right
  after every await resume point and jumps straight to `__finally__` (the default
  `_return_val` from `__prepare__` is already in place), cleans up stack-owned values, and
  returns `MCO_DEAD`. Cancel must not finalize: no `done`, no pack, no emit, no waiter
  resume — the waiter list is only popped to release awaiter reference edges, so abandoned
  awaiters stay suspended forever (aligned with Godot `cancel_pending_functions`). A default
  value the cancel path wrote into the typed return slot never reaches `result_cache`; like
  every path, the slot is destroyed exactly once by `free_instance` through
  `desc->destroy_ret_slot`. `co == NULL` (the coroutine frame was never
  created, e.g. OOM in the thunk) is tolerated: cancel does not resume anything, and
  `free_instance` still performs its ordinary cleanup of the already-initialized state
  fields.
- `free_instance` of a state class asserts the coroutine is `MCO_DEAD` or never-created
  (`co == NULL`, OOM path; via `gdcc_coro_state_free`), then — in generated code — calls
  `desc->destroy_ret_slot` exactly once, destroys the typed parameter fields and frees the
  wrapper.

### Compile wiring

`CProjectBuilder.buildProject(...)` appends `<includeRoot>/gdcc/minicoro.c` and
`<includeRoot>/gdcc/gdcc_coroutine.c` to the native compiler inputs next to
`<includeRoot>/godot/godot_binding.c`. The `godot_binding.c` aggregation structure is
unchanged. Resource extraction already covers the whole `gdcc/**` tree, so the new files
need no extra extraction rules.

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
  trailing `const godot_Variant **argv, godot_int argc` convention. Vararg wrappers size the
  stack argv array as `fixed + (argc > 0 ? argc : 1)` and pass `NULL` when `fixed + argc == 0`.
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
