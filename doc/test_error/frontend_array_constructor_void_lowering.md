# Frontend Array Constructor Void Lowering Gap

## Summary

While adding end-to-end `test_suite` coverage for source-level subscript and dynamic-call flows, a reproducible build-time failure appeared for executable-body `Array()` construction in certain downstream usages.

Observed failure:

- frontend lowering succeeds
- backend C generation aborts on `construct_builtin`
- reported error:
  - `Builtin constructor validation failed: 'void' with args [] is not defined in ExtensionBuiltinClass`

This indicates that the pipeline is emitting or preserving an invalid builtin-constructor type for this path.

## Reproduced Source Shapes

Two source shapes triggered the same failure during end-to-end expansion:

1. Local `Array()` followed by indexed operations

```gdscript
func compute() -> int:
    var values: Array = Array()
    values[1] = 6
    var first: int = values[0]
    return first
```

2. Local `Array()` inside a dynamic-call probe helper flow

```gdscript
func dynamic_size(value):
    return value.size()

func compute() -> int:
    var plain: Array = Array()
    plain.push_back(1)
    return dynamic_size(plain)
```

## Current Evidence

- Existing positive coverage already proves that some `Array()` paths work, for example a simple local initializer followed by `size()` in `initializer/local/constructors_and_constants.gd`.
- The failing cases are therefore not “Array() is globally unsupported”.
- The failure only appears once the executable-body flow expands into additional operations such as indexed access or the broader dynamic-call probe.

## Cause Chain

The backend receives a `construct_builtin` instruction whose builtin type is already `void`.

At that point `ConstructInsnGen` rejects the instruction because extension metadata obviously has no builtin constructor table for `void`.

So the broken link happens upstream of final C emission:

- either frontend lowering publishes an invalid builtin-construction type for this path
- or some intermediate lowering/materialization step corrupts a previously valid builtin type before codegen consumes it

## Impact

This blocks stable end-to-end coverage for source programs that:

- construct an `Array` in executable body
- then continue with indexed mutation/load or similar richer downstream flows

So current executable-body builtin-container constructor support is narrower than the simple passing smoke cases suggest.

## Suggested Fix Direction

Investigate the lowering path that produces builtin constructor instructions for executable-body container locals and compare it with the already passing local-initializer `Array()` route.

The first regression test after fixing should cover:

- local `Array()` in executable body
- at least one downstream indexed store/load use
- a second case that routes the constructed array through the current dynamic-call surface
