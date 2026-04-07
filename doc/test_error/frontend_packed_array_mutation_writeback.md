# Frontend Packed Array Mutation Writeback Gap

## Summary

While expanding the resource-driven end-to-end `test_suite`, a packed-array case exposed a real implementation gap in the current MVP pipeline:

- source shape:
  - create a local `PackedInt32Array`
  - mutate it through methods such as `push_back(...)`
  - later read it through source-level indexed access
- observed result:
  - native build succeeds
  - Godot runtime reports `variant_get_indexed failed`
  - the validation script does not see the pushed values

This is not a test-authoring mistake. It indicates that the current pipeline does not preserve receiver writeback for value-semantic packed-array mutations in this path.

## Reproduction

Minimal reproducer used during `GdScriptUnitTestCompileRunnerTest` expansion:

```gdscript
class_name PackedArrayRoundtripSmoke
extends Node

func compute() -> int:
    var values: PackedInt32Array = PackedInt32Array()
    values.push_back(4)
    values.push_back(5)
    values.push_back(6)
    return values[0] * 100 + values[1] * 10 + values[2]
```

Observed runtime output:

- `variant_get_indexed failed: self=$cfg_tmp_v10, index=$cfg_tmp_v11, result=$cfg_tmp_v12`

## Cause Chain

Generated C for the failing case shows the mutation happens on a temporary packed-array copy:

- the local source slot is copied into a temp such as `$cfg_tmp_v7`
- `godot_PackedInt32Array_push_back(&$cfg_tmp_v7, ...)` mutates that temp
- no writeback happens from the mutated temp back into the original source local
- later indexed load creates a fresh copy from the original local again
- that original local is still empty, so indexed access fails at runtime

In other words, the broken link is:

- value-semantic packed-array receiver mutation
- missing receiver writeback after the mutating method call
- later frontend-lowered indexed read observing the stale original value

## Scope

Confirmed failing source pattern:

- local `PackedInt32Array`
- mutating method call
- later indexed read in the same function

The current evidence does **not** show that raw backend `VariantSetIndexedInsn` / `VariantGetIndexedInsn` support is universally broken:

- `IndexStoreInsnGenEngineTest` already exercises packed-array set/get at the backend instruction level and passes
- the failing path appears when frontend lowering produces source-level packed-array mutation flow without preserving receiver writeback

So the likely fault domain is the frontend-lowered receiver/mutation path, not the basic backend indexed helper contract by itself.

## Impact

Current MVP documentation treats packed-array family subscript as supported. In practice, real source programs that rely on prior packed-array mutation can still fail because the mutated receiver state is lost before later reads.

This means the existing support surface is incomplete for packed-array mutation flows.

## Suggested Fix Direction

Investigate the lowering/codegen path for value-semantic mutating member calls on packed arrays and make receiver writeback explicit when source semantics require the mutation to persist.

The fix should be validated with an end-to-end regression that covers:

- local packed-array mutation by method call
- later indexed read from the same local
- both happy path and a negative/control comparison when no mutation happened
