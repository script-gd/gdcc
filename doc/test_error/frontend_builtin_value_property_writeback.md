# Frontend Builtin Value Property Writeback Gap

## Summary

During end-to-end `test_suite` expansion, source-level builtin property **write** on value-semantic builtin receivers was found to compile and build successfully but lose its mutation at runtime.

Confirmed reproducer family:

- local builtin value such as `Vector3` or `Color`
- property setter style source write
- later source-level read from the same logical variable

Observed result:

- runtime still sees the original value
- for example `vector.x = vector.y + vector.z` followed by `return vector.x` returned `1.0` instead of `5.0`

## Reproducer

```gdscript
class_name BuiltinPropertyAccessSmoke
extends Node

func vector_x_after_write() -> float:
    var vector: Vector3 = Vector3(1.0, 2.0, 3.0)
    vector.x = vector.y + vector.z
    return vector.x
```

Expected result:

- `5.0`

Observed result:

- `1.0`

## Cause Chain

Generated C shows the setter is emitted on a temporary copy rather than on the source local that subsequent reads observe:

- `$vector` is initialized from the constructor result
- multiple temporary copies such as `$cfg_tmp_v4`, `$cfg_tmp_v5`, `$cfg_tmp_v7` are created from `$vector`
- `godot_Vector3_set_x(&$cfg_tmp_v4, ...)` mutates only the temp copy
- later read path clones `$vector` again into another temp and reads from that untouched original value

So the broken chain is:

- source-level builtin property write on a value-semantic receiver
- lowering/codegen mutates a receiver temp copy
- no writeback from the mutated temp back into the original source slot
- later read still sees the stale original value

The same structural pattern is likely to affect other value-semantic builtin property setters, not only `Vector3.x`.

## Scope

Confirmed symptom:

- local `Vector3` property setter writeback is lost

The code shape suggests that other value-semantic builtin receivers such as `Color` may be affected by the same missing writeback rule.

Read-only builtin property access is still separately validated and currently works.

## Impact

Current implementation documents builtin instance property write as part of the compile-ready surface. In practice, source-level writes on value-semantic builtin locals are not yet semantically closed because the mutated receiver state is discarded.

This means builtin property support is currently asymmetric:

- read path works
- write path on value-semantic builtin locals is incomplete

## Suggested Fix Direction

Introduce explicit receiver writeback for value-semantic builtin property stores when source semantics require the mutation to persist.

The eventual regression coverage should include:

- builtin property write on a local `Vector3`
- builtin property write on another value-semantic builtin such as `Color`
- a control case proving read-only builtin property access still keeps its current behavior
