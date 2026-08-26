# GDCC C Backend Lifecycle and Ownership Specification (Unified)

> Status: Active baseline (Step 1 synced on 2026-04-09)  
> Scope: Code generation semantics under `src/main/java/gd/script/gdcc/backend/c/**` and `src/main/c/codegen/**`

## 1. Background and Goals

The current backend has two coexisting intuitions:

1. **Return transfers ownership** (object values returned by functions are owned by the caller by default)
2. **Storage implies ownership** (a variable/field slot that stores an object should be responsible for that reference)

This specification unifies both into one executable model, aiming to prevent:

- Duplicate `own` operations (extra +1 reference count)
- Missing `release` (leaks)
- Premature `release` (dangling references or incorrect behavior)
- Treating pointer representation conversion as an ownership operation

## 2. Terms

### 2.1 Value Ownership Category

- `BORROWED`: Borrowed-only value; does not carry consumable ownership.
- `OWNED`: Carries one consumable ownership; it must be consumed exactly once.

### 2.2 Slot

A writable storage location, including but not limited to:

- LIR variables (non-`ref`)
- Object fields (property backing fields)
- Implicit return slot `_return_val`
- Declared writable temporary variables (`TempVar`)
- Coroutine frame owning fields (typed parameter fields, typed return slot; see 3.10)

### 2.3 Representation Conversion

Internal object **values** are per-static-type fat pointers (`gdcc_<Type>_fat_ptr`). Representation conversion
covers both:

1. Fat pointer ↔ validated live raw Godot pointer (`<Type>_fat_ptr_from_raw` / `<Type>_fat_ptr_live_object`,
   and same-type or upcast fat-to-fat helpers that preserve `instance_id`)
2. GDCC **wrapper** pointers (`<Type*>`) ↔ Godot raw object pointers at layout/ABI edges
   (for example `gdcc_object_from_godot_object_ptr(...)`, `gdcc_object_to_godot_object_ptr(obj, Class_object_ptr)`)

Do not confuse wrapper instance layout (`<Type*>` / `_object` / `_super`) with object-value storage
(`gdcc_<Type>_fat_ptr`).

**Representation conversion does not change ownership category.**
An `OWNED` value stays `OWNED` after conversion, and a `BORROWED` value stays `BORROWED`;
slot-write / discard / `_return_val` publish remain the only boundaries that may add or consume ownership.

## 3. Unified Semantic Rules (Normative)

### 3.1 Production Rules

- Fresh object-producing routes produce `OWNED` by default:
  - function calls
  - method calls
  - constructor/materialization helpers
  - property-init helpers that semantically return a fresh object value
- Reading object values from existing storage produces `BORROWED` by default:
  - local variables
  - parameters
  - backing fields / `self` field reads
  - property reads
  - index reads
- Raw expression wrappers stay `BORROWED` unless the producer route explicitly upgrades them to `OWNED`.
- `literal_null` / `NULL` is treated as `BORROWED` (no release needed for null).
- Pure representation-converted values keep their original ownership category.

### 3.2 Slot Write Rules

Writing any object value into a slot must follow this order:

1. If the slot is initialized and has an old value, capture the old value into a temporary slot.
2. Write the new value (including pointer representation conversion if needed).
3. Decide whether to `own` based on RHS ownership:
   - RHS is `BORROWED`: must `own` the new value.
   - RHS is `OWNED`: must not `own` again; consume ownership directly.
4. Release the captured old value (`release` or `try_release` variant).
5. Mark the slot as initialized.

Implementation note:

- Non-object slot writes are outside object ownership transfer, but can still be centralized with a helper (for example `emitNonObjectSlotWrite`) as long as existing non-object lifecycle order is preserved:
  - prepare/copy RHS first,
  - destroy old value when required,
  - then assign.
- Such consolidation is a structural refactor and must not change copy/destroy semantics by itself.

### 3.3 Overwrite vs First Write

- For first write to an uninitialized slot, skip step 1 (no old value to release).
- Variable initialization in `__prepare__` is first-write semantics and must not clean old value.
- Constructor-time property initializer apply is also a first-write route:
  - it writes the backing field directly
  - it must not be modeled as a property setter call
  - object-valued apply still follows the unified slot-write core for ptr conversion and ownership consume
  - it skips old-value release because constructor-time property apply is not an overwrite route

### 3.4 Return Rules

- Object values returned to the caller are considered `OWNED`.
- In non-`__finally__` paths: write to `_return_val`, then `goto __finally__`.
- Writing `_return_val` follows the same slot write rules from 3.2:
  - borrowed source -> retain in `_return_val`
  - owned source -> consume directly into `_return_val`
- Returning an owning local object slot moves that slot into `_return_val`:
  - write `_return_val` with `OWNED` rhs semantics
  - clear the source slot before entering `__finally__`
  - this prevents local auto-destruction from releasing the published return object again
- On the LIR surface, a non-`void` `__finally__` block must terminate with `ReturnInsn("_return_val")`.
  Direct `ReturnInsn(<user-var>)` in `__finally__` is invalid backend IR and is rejected before C emission.
- `_return_val` is outside the LIR variable table auto-destruction scope (it is published through return flow).
- Coroutine body functions keep this contract unchanged; their `__finally__` consumes `_return_val`
  into the frame's typed return slot instead of a C `return` (that consume **is** the coroutine
  function's real return publish, see 3.10).

### 3.5 Discard Rules

- Discarding an `OWNED` object return value: must immediately `release` (or `try_release` variant).
- Discarding a `BORROWED` object value: no cleanup required.
- For non-object but `isDestroyable()==true` return values (String/Variant/Container, etc.), discarding must immediately `destroy`.

### 3.6 RefCounted Status Matrix

Select operation by `RefCountedStatus`:

- `YES`: `own_object` / `release_object`
- `UNKNOWN`: `try_own_object` / `try_release_object`
- `NO`: object own/release is a no-op

The `try_*` helpers detect RefCounted at runtime via the ObjectID reference bit (bit 63), not a ClassDB
class-name query. They receive the validated live raw pointer (`<T>_fat_ptr_live_object(v)`) plus the fat
pointer's cached `instance_id` (`v.instance_id`); the ID is never recovered from the raw pointer. The
precise (`own_object` / `release_object`) variants stay single-argument.

`ClassRegistry.getRefCountedStatus` mapping notes:

- Exact engine type `Object` is `UNKNOWN`: an Object-typed slot/return may hold a live `RefCounted`
  instance, so ownership boundaries must use runtime `try_*` helpers.
- Definite non-`RefCounted` subclasses (`Node`, …) stay `NO`.
- Definite `RefCounted` / `Resource` types stay `YES`.
- GDCC user classes that inherit `Object` without reaching `RefCounted` stay `NO` (they are not the
  engine root-Object special case).

Automatic local cleanup rule:

- `AUTO_GENERATED` `destruct` in `__finally__` is slot-based cleanup for managed locals still owned by the
  current function, not a blanket rule over every live object value.
- `AUTO_GENERATED` `destruct` in `__finally__` must never destroy definite non-`RefCounted` object locals.
- Scope-exit cleanup for object locals is only allowed to release reference-managed object slots:
  - `YES` -> `release_object`
  - `UNKNOWN` -> `try_release_object`
  - `NO` -> no cleanup
- `_return_val` is the hidden return-publish slot, not a normal local variable entry, so it is excluded from
  the auto-cleanup set by contract.
- This matches Godot's contract where non-`RefCounted` objects stay under explicit user-managed lifetime (`free`, `queue_free`, etc.) even when stored in local variables.

### 3.7 Constraints

- Do not infer ownership from function name prefixes (e.g. `godot_`).
- Do not treat `gdcc_object_from_godot_object_ptr(...)` as a retain operation.
- `OWNED` values must be consumed exactly once; repeated consumption is forbidden.

### 3.8 Explicitly Rejected Shortcuts

- Reject “retain every object return once before function exit”.
  - Fresh `OWNED` call results are already caller-owned at the producer boundary.
  - Re-retaining them at function exit leaks one reference.
- Reject “release every object slot once at function exit”.
  - Scope-exit cleanup applies only to managed local slots.
  - `_return_val`, moved-out sources, `ref` locals, and definite non-`RefCounted` locals are outside that blanket model.

### 3.9 Lifecycle Instruction Provenance Restrictions

Lifecycle instructions are controlled by provenance and validated before backend generation.

`LifecycleProvenance`:
- `AUTO_GENERATED`: inserted by compiler automation (`__finally__` destruct path).
- `INTERNAL`: compiler internal lifecycle maintenance for temp/internal variables.
- `USER_EXPLICIT`: lowered from explicit lifecycle intent in user GDScript source.
- `UNKNOWN`: legacy/default marker; allowed only in compatibility mode.

Allowed/forbidden matrix:

| Provenance | Allowed | Forbidden |
| --- | --- | --- |
| `AUTO_GENERATED` | `destruct` in `__finally__` auto-generated flow | Any non-`__finally__` block, `try_own_object`, `try_release_object` |
| `INTERNAL` | Internal/temp variables (numeric IDs or `__*` IDs) | Ordinary user-named variables, parameters |
| `USER_EXPLICIT` | Explicit user-intent lowered instructions in normal blocks | Auto-generated blocks (`__prepare__`, `__finally__`) |
| `UNKNOWN` | Compatibility mode with warning | Strict mode |

Strict/compat policy:
- Compat mode (`strictMode=false`): `UNKNOWN` emits warning and passes.
- Strict mode (`strictMode=true`): `UNKNOWN` and invalid provenance usage fail fast.

Interaction with `__prepare__` / `__finally__`:
- `__finally__` auto-destruct remains enabled and uses `AUTO_GENERATED`.
- For object locals, that auto-destruct path is refcount-only; it must not synthesize destruction for definite non-`RefCounted` types.
- `USER_EXPLICIT` is rejected in `__prepare__` and `__finally__` to avoid semantic collision with auto lifecycle flow.
- Conflict is resolved by validation stage before code generation.

### 3.10 Coroutine State Object and Frame Ownership

Applies to functions marked `is_coroutine="true"` (see `gdcc_low_ir.md` §Coroutine Instructions).
The coroutine frame and its Godot-visible state object are one entity; reference counting is
delegated to the engine `RefCounted` mechanism (`Variant(Object)` store/load retains/releases
automatically) — the compiler must not invent a manual frame refcount. At the LIR level the
call site's state reference is a `compiler::GdccCoroState` variable (C storage
`godot_Object*`) — a single-consumer owned value per `gdcc_low_ir.md` §Coroutine
Instructions; crossing into or out of `Variant` happens only inside runtime helpers and
engine-boundary wrappers, never in generated body code.

Static coroutine functions have no source-level `self` receiver (see
`frontend_await_implementation.md` §5). Their state object and frame therefore contain no
implicit `self` field; this does not change the ownership or keep-alive rules below — any explicit
object parameter is retained only through its ordinary parameter storage, and the state object is
kept alive by the same wait edges as instance coroutines.

Keep-alive edges have a fixed direction (no cycles):

- Signal wait: the connection's custom Callable holds a reference to the awaiting coroutine's
  **own** state object (root edge, aligned with Godot's `bind(retvalue)`).
- Coroutine-chain wait: the callee's waiter node holds a reference to the **awaiter** state
  object (callee → awaiter). The call site's OWNED callee reference is released by
  `gdcc_coro_await_state` right after waiter registration and **before** the yield (dynamic
  own-state path: the operand Variant's callee reference is released and the operand reset
  to nil before the yield), so the callee stays alive through its own wait edges and the
  awaiter never holds the callee across a suspension.
- Dynamic external-object wait: the awaiter frame keeps the operand Variant (and therefore
  the emitter object) retained for the whole suspension, because Signal connections do not
  keep the emitter alive (aligned with Godot's keep-alive direction). This edge is
  forbidden on the own-state path above.
- A permanently suspended chain leaks, matching Godot semantics.

Suspension never triggers the return path. `__finally__` runs exactly once, when the body
function truly returns (including the cancel-resume path); locals live on the minicoro stack
for their entire lifetime. The backend must not invent a second cleanup logic for coroutine
functions.

Parameters of a coroutine function:

- The frame's typed parameter fields are the sole owning storage; **no parameter C slots are
  generated**. Body parameter operands map directly to frame fields, and parameter writes
  follow the ordinary slot-write rules (3.2) applied to those fields.
- `__prepare__` does not initialize parameters and `__finally__` does not clean them; the
  entry thunk fills them per slot-write rules (borrowed parameters are retained, destroyable
  ones copied). Parameter fields are destroyed exactly once, by `free_instance` — the cancel
  path never touches them (after cancel-resume the coroutine is `MCO_DEAD` and flows into
  the same single `free_instance` cleanup).

Captures of a coroutine lambda follow the same per-call frame discipline:

- The frame's typed `_coro_capture_<name>` fields are the sole owning storage for captures;
  no `_capture` prologue locals are generated and the body's `__finally__` never touches
  them. Body capture operands map directly to frame fields.
- The start thunk copies each field out of the Callable-owned capture block before
  `mco_create` (primitives assigned, objects retained from a BORROWED source, value types
  copy-constructed); the capture block itself remains owned solely by the Callable userdata
  and is freed independently by its `free_func` — releasing the Callable while suspended
  therefore never invalidates the frame.
- Capture fields are destroyed exactly once, by `free_instance` after the parameter fields;
  the cancel path flows into the same single cleanup. Writes to a capture name inside the
  lambda hit only that call's frame copy, matching copy-on-capture semantics.

Return-value storage state machine (must not be violated):

1. The body `__finally__` consumes `_return_val` into the typed return slot (`_return_val` is
   considered cleared afterwards).
2. `gdcc_coro_finalize`: copies the slot into `result_cache` (copy, not move — the slot stays
   live for the typed resume channel) and sets `done` — `done`/`result_cache` are visible
   before any waiter resume or `completed` emit; each typed waiter then receives its resume
   value through `desc->copy_ret_slot`, each Variant waiter through a
   `godot_variant_new_copy` from `result_cache`.
3. On `MCO_DEAD` the internal coroutine-start thunk finalizes (if not yet done) and returns
   the OWNED `done` state object as-is — it never moves the typed return slot. Only the
   ClassDB call/ptrcall wrapper performs the synchronous-completion fast path: **move** the
   result directly out of the typed return slot through the state class's own generated
   move accessor (`<state>__move_result`; the slot is left in a valid moved-from state),
   then release the state object reference and return the declared value.
4. `free_instance` destroys the always-constructed `result_cache`, the typed parameter
   fields, the typed coroutine-lambda `_coro_capture_*` fields (exactly once, after the
   parameter fields), the typed return slot via `desc->destroy_ret_slot` (exactly once on
   every path — completion, ClassDB-wrapper synchronous fast path and cancel alike;
   tolerates never-written and moved-from slots) and the `mco_coro` (skipped when
   `co == NULL` on the OOM path).

Signal-resume callback argument Variants are only copied, never consumed; each Variant-kind
waiter receives its own `godot_variant_new_copy` of the result, each typed waiter a
`desc->copy_ret_slot` copy.

Cancel-resume (abandonment path, e.g. emitter death dropping the last reference):

- Hooked on the state class `NOTIFICATION_PREDELETE`, not on `free_instance`; the state class
  destructor must not touch frame fields.
- `gdcc_coro_cancel` is mutually exclusive with `gdcc_coro_finalize`: set `cancel`, resume a
  `MCO_SUSPENDED` coroutine, and the body jumps straight to `__finally__` at the nearest await
  resume point (the default `_return_val` from `__prepare__` is already in place), cleans up
  stack-owned values, and returns `MCO_DEAD`.
- Cancel never sets `done`, never packs `result_cache`, never emits, and never resumes
  waiters — waiter edges are only released, so abandoned awaiters stay suspended forever
  (aligned with Godot `cancel_pending_functions`). A default value written into the typed
  return slot on the cancel path never reaches `result_cache`; it is left for the single
  `free_instance` `desc->destroy_ret_slot` call (state machine rule 4).
- The cancel check after every await resume point is generated uniformly by `AwaitInsnGen`;
  the cancel path must not call any user code.

## 4. Alignment with Current Backend Structure

### 4.1 CBodyBuilder

- `assignVar` / `callAssign` / `returnValue` must explicitly carry or infer value ownership.
- Object slot write logic must be unified into a single path to avoid branch drift.
- `discardRef` branch must perform cleanup per rule 3.5.

### 4.2 CCodegen

- Keep `__prepare__` / `__finally__` framework unchanged.
- `_return_val` is still generated and managed by `CBodyBuilder`, and must not be moved into variable-table auto-destruction.
- Property initializer lowering may materialize helper-produced values, but constructor-time application of those values to backing fields remains a separate backend-owned route.
- Coroutine body functions reuse the same `__prepare__` / `__finally__` framework unchanged;
  coroutine frame fields (typed parameter fields, typed return slot) are not ordinary C local
  slots and stay outside the variable-table auto-destruction scope, exactly like `_return_val`.

### 4.3 Instruction Generators

- Continue emitting assignment/call code through Builder APIs.
- Keep generators focused on semantic validation; do not hand-write object lifecycle code.

### 4.4 Generated call_func Wrappers

- Generated GDExtension `call_func` wrappers own a narrow class of wrapper-local values that never enter the ordinary Builder slot model:
  - argument locals unpacked from `Variant`
  - local `ret` used to publish the outward `Variant` return
  - local non-`void` return carrier `r`
- Cleanup rule for those locals is value-wrapper specific:
  - destroyable non-object wrappers must be explicitly destroyed before the wrapper returns
  - `OWNED` object return carrier `r` must be released after Variant packing
    (`release_object` / `try_release_object` per `RefCountedStatus`) so internal ownership
    transfers net-zero into `r_return`
  - object argument locals are BORROWED from Variant args and must not be released here
  - primitives never need wrapper cleanup
- Required success-path order:
  1. publish `r_return`
  2. destroy local `ret`
  3. release `OWNED` object return carrier `r` (when RefCounted YES/UNKNOWN)
  4. destroy destroyable non-object `r`
  5. destroy wrapper-owned argument locals in reverse order
- This rule complements, but does not replace, the function-body ownership model in Section 3:
  - Builder-managed slots still follow the unified slot-write/return/discard rules
  - wrapper locals stay a template-owned responsibility boundary

## 5. Compatibility and Migration Constraints

- LIR lifecycle instructions (`destruct`, `try_own_object`, `try_release_object`) now support an optional provenance token.
  - Legacy format without provenance is interpreted as `UNKNOWN`.
- This specification does not change `ClassRegistry` assignability rules.
- If template layer (FTL) still contains object lifecycle logic, it must match this specification.
- Strict rollout is staged:
  - Compat phase: collect and burn down `UNKNOWN` warnings.
  - Strict phase: reject unknown/illegal provenance.

## 6. Acceptance Criteria

The semantics are considered implemented when all conditions hold:

1. No duplicate `own` in object-call-result assignment paths.
2. No leaks of destroyable return values in non-void discard paths.
3. Repeated overwrite of `_return_val` does not leak and does not release too early.
4. Pointer representation conversion paths do not alter ownership behavior.
5. Unit tests cover all `YES/UNKNOWN/NO` RefCounted status cases.
