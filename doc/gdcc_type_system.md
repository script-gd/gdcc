# Types

## Overview
- All concrete types extend a base abstract `GdType`. `GdType` supplies a type name, equality/compatibility checks, and serialization helpers.
- Logical groupings:
    - Primitive types: `GdPrimitiveType` and subclasses (`GdIntType`, `GdFloatType`, `GdBoolType`, `GdStringType`, ...).
    - Vector/geometry types: `GdVectorType`, `GdFloatVectorType`, `GdQuaternionType`, `GdTransform3DType`, etc.
    - Container types: `GdArrayType`, `GdDictionaryType`, and `GdPacked*` variants.
- Object/reference types: `GdObjectType`, `GdNodePathType`, `GdRidType`, `GdSignalType`, `GdCallableType`.
- Meta/extension types: `GdMetaType`, `GdExtensionTypeEnum` for annotations and extension points.
- Compiler-only types: `GdCompilerType` as the shared abstraction for backend-only storage types (for-iter state types including per-family `GdccForPackedArrayIterType` instances, etc.).

## Compiler-only Types

- `GdCompilerType` models GDCC-owned runtime storage that only exists inside compiler / lowering / LIR / backend pipelines.
- Compiler-only types are not part of the GDScript source-facing type set:
  - declared type parsers must not resolve them
  - type-meta and outward ABI metadata must not publish them
  - ordinary user-facing semantic facts such as `expressionTypes()` and ordinary slot typing must not expose them
- Concrete `GdCompilerType` examples include `GdccForRangeIterType` and per-family
  `GdccForPackedArrayIterType` instances (one state type per Packed*Array family).
- Compiler-only types may participate in LIR/backend-local assignment and intrinsic contracts, but they are outside the ordinary frontend assignment/conversion matrix.

## Major Types (Summary)
- `GdPrimitiveType`: atomic values.
    - `GdIntType`: integers.
    - `GdFloatType`: floating point numbers.
    - `GdBoolType`: booleans.
    - `GdNilType`, `GdVoidType`: nil / no-return placeholders.
    - `GdVariantType`: dynamic/any type placeholder (compatible with all).
- `GdStringLikeType`: string representations, logically atomic but actually a pointer to COW data.
    - `GdStringType`: UTF-8 strings.
    - `GdNodePathType`: node path strings.
    - `GdStringNameType`: StringName for identity check.
- Vectors & geometry:
    - `GdVectorType`, `GdPureVectorType`, `GdFloatVectorType`, `GdIntVectorType`: vectors of various element types and dimensionality.
    - `GdQuaternionType`, `GdTransform2DType`, `GdTransform3DType`, `GdPlaneType`, `GdAABBType`, `GdRect2Type`, `GdRect2iType`.
- Containers:
    - `GdArrayType`: generic arrays (may carry an element type parameter).
    - `GdPackedArrayType` family: optimized packed arrays for numeric, string, vector types.
    - `GdContainerType`: container abstraction for shared behavior.
    - `GdDictionaryType`: key/value mapping with optional key/value type parameters.
- Objects & callables:
    - `GdObjectType`: references to Godot objects or class instances; may carry class name constraints.
    - `GdRidType`: specialized reference type for opaque pointers.
    - `GdSignalType`, `GdCallableType`: signal and callable value representations.
- Compound & semantic types:
    - `GdCompoundVectorType`, `GdProjectionType`, `GdColorType`, etc., for richer semantics.
- Meta & extension:
    - `GdMetaType`: holds annotations/metadata to assist code generation and IDE features.
    - `GdExtensionTypeEnum`: enumerated extension options.

## Container Type Boundaries

- `GdArrayType` and `GdPackedArrayType` are different container families:
  - `GdArrayType` models `Array[T]` with optional element typing metadata.
  - `GdPackedArrayType` models concrete packed containers (for example `PackedInt32Array`, `PackedVector3Array`), and is not represented as `Array[T]`.
- For extension metadata normalization in backend type parsing:
  - `typedarray::Packed*Array` maps to the corresponding `GdPacked*ArrayType`.
  - non-packed `typedarray::T` maps to `GdArrayType(T)`.

## Size & Layout
- `PrimitiveSize.java` provides size references for basic types used by binary serialization, alignment, and packed array optimizations.
- Packed arrays aim for compact binary layout for memory/disk efficiency.

## Compatibility & Promotion Rules
- Backend assignment compatibility (used by `ClassRegistry#checkAssignable` globally):
    - Same type is assignable.
    - Object types support inheritance upcast.
    - Container covariance is limited to:
      - `Array[T]` -> `Array` / `Array[Variant]`
      - `Array[SubClass]` -> `Array[SuperClass]`
      - `Dictionary[K, V]` -> `Dictionary` / `Dictionary[Variant, Variant]`
      - `Dictionary[K1, V1]` -> `Dictionary[K2, V2]` when both key/value directions are assignable.
- Other implicit promotions (for example numeric promotion) are not part of generic assignment compatibility and must be handled by dedicated lowering/instruction semantics.
- Compiler-only types are not part of the ordinary assignment-compatibility matrix; they are handled by LIR/backend-specific contracts and must not be treated as source-facing declared types.
- For "TypeType", which is a type representing another type:
  - e.g. `var N = Node` where `N` is a "TypeType" representing the `Node` type.
  - We do not explicitly model "TypeType" in the type system; instead, we use a `StringName` to represent the type name as the implementation detail.
  - When we detect `some_str_name.new()`, we treat it as a constructor call for the type named by `some_str_name`.

## Mutating Receiver Writeback Families

- This section defines the shared type-system fact used by frontend writable-route lowering and backend runtime helpers to decide whether a mutating call on a receiver may need reverse writeback into an outer owner.
- The question answered here is:
  - "If this receiver family is mutated through a leaf access route, does the updated leaf need to be written back into the outer owner?"
- This is intentionally different from backend-local questions such as:
  - "Can this type be used as the `self` operand of a particular `variant_set_*` codegen path?"
- Therefore backend generators such as `IndexStoreInsnGen` are not the truth source of this rule. They may consume or mirror it locally, but they must not define it.

The current shared rule is:

- does not require writeback:
  - primitive family
  - `Object` family
  - shared/reference container family (`Array`, `Dictionary`)
- requires writeback:
  - other instance-call-capable value-semantic builtin families, including packed arrays

The intended interpretation is:

- primitive family:
  - no mutating receiver route should rely on reverse owner commit
- `Object` family:
  - mutation happens through reference identity, so outer-owner writeback is not the semantic carrier
- shared/reference container family:
  - `Array` / `Dictionary` ownership is not modeled as "mutate leaf then commit into owner" in the same way as value-semantic builtin structs
- value-semantic builtin families such as `String`, `StringName`, `NodePath`, `Color`, `Vector*`, `Basis`, `Transform*`, `Quaternion`, `Rect*`, `Plane`, `AABB`, `Projection`, `Callable`, `Signal`, `RID`, `Packed*Array`:
  - if a mutating call targets a leaf reached through property/subscript/nested access, the leaf may need reverse writeback to preserve Godot-observable behavior

For static typing:

- frontend/shared semantic should answer this rule from `GdType` family information and published semantic facts
- public code anchor: `FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(...)`
- frontend writable-route lowering should first use the static shortcut:
  - statically known shared/reference families skip the current writeback layer directly
  - statically known value-semantic families apply the current layer directly
- dynamic/`Variant` receiver routes are the only remaining runtime-open branch, so they must defer to the runtime helper `gdcc_variant_requires_writeback(...)`
- the helper contract is currently frozen as:
  - returns `false` for `NIL`, `BOOL`, `INT`, `FLOAT`, `ARRAY`, `DICTIONARY`, `OBJECT`
  - returns `true` for `String`, `StringName`, `NodePath`, `Vector*`, `Rect*`, `Plane`, `Quaternion`, `AABB`, `Basis`, `Transform*`, `Projection`, `Color`, `RID`, `Callable`, `Signal`, `Packed*Array`
  - returns `true` by default for unlisted future `Variant` kinds, so a newly introduced value-semantic carrier cannot silently tunnel through runtime-gated writeback as a false negative

If this matrix changes, the following fact sources must be updated together:

- this document
- `frontend_complex_writable_target_implementation.md`
- `FrontendWritableTypeWritebackSupport` 与 receiver-side writable-route helpers in frontend lowering
- runtime `Variant` writeback helper contracts in `gdcc_helper.h`


## Serialization & Text Representation
- Each type should export a stable string representation for documentation, diagnostics, and script annotations.
- Packed arrays use compact binary formats when serialized.
- Textual representations aim to be consistent with Godot type names for easier mapping to target languages or the Godot API.
