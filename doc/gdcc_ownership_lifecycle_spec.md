# GDCC C Backend Lifecycle and Ownership Specification (Unified)

> Status: Active baseline (Step 1 synced on 2026-04-09)  
> Scope: Code generation semantics under `src/main/java/dev/superice/gdcc/backend/c/**` and `src/main/c/codegen/**`

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

### 2.3 Representation Conversion

- Conversion between GDCC object pointers (`<Type*>`) and Godot raw object pointers
- For example: `gdcc_object_from_godot_object_ptr(...)`, `gdcc_object_to_godot_object_ptr(obj, Class_object_ptr)`

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

### 3.5 Discard Rules

- Discarding an `OWNED` object return value: must immediately `release` (or `try_release` variant).
- Discarding a `BORROWED` object value: no cleanup required.
- For non-object but `isDestroyable()==true` return values (String/Variant/Container, etc.), discarding must immediately `destroy`.

### 3.6 RefCounted Status Matrix

Select operation by `RefCountedStatus`:

- `YES`: `own_object` / `release_object`
- `UNKNOWN`: `try_own_object` / `try_release_object`
- `NO`: object own/release is a no-op

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

## 4. Alignment with Current Backend Structure

### 4.1 CBodyBuilder

- `assignVar` / `callAssign` / `returnValue` must explicitly carry or infer value ownership.
- Object slot write logic must be unified into a single path to avoid branch drift.
- `discardRef` branch must perform cleanup per rule 3.5.

### 4.2 CCodegen

- Keep `__prepare__` / `__finally__` framework unchanged.
- `_return_val` is still generated and managed by `CBodyBuilder`, and must not be moved into variable-table auto-destruction.
- Property initializer lowering may materialize helper-produced values, but constructor-time application of those values to backing fields remains a separate backend-owned route.

### 4.3 Instruction Generators

- Continue emitting assignment/call code through Builder APIs.
- Keep generators focused on semantic validation; do not hand-write object lifecycle code.

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
