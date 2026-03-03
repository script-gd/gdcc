# Types

## Overview
- All concrete types extend a base abstract `GdType`. `GdType` supplies a type name, equality/compatibility checks, and serialization helpers.
- Logical groupings:
    - Primitive types: `GdPrimitiveType` and subclasses (`GdIntType`, `GdFloatType`, `GdBoolType`, `GdStringType`, ...).
    - Vector/geometry types: `GdVectorType`, `GdFloatVectorType`, `GdQuaternionType`, `GdTransform3DType`, etc.
    - Container types: `GdArrayType`, `GdDictionaryType`, and `GdPacked*` variants.
    - Object/reference types: `GdObjectType`, `GdNodePathType`, `GdRidType`, `GdSignalType`, `GdCallableType`.
    - Meta/extension types: `GdMetaType`, `GdExtensionTypeEnum` for annotations and extension points.

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
- Each type implementation provides compatibility rules. Common rules:
    - `GdVariantType` is compatible with any type.
    - Numeric promotion: e.g., `int` promoted to `float` when required by an operation; reverse may require explicit cast.
    - Containers: arrays with compatible element types can be assigned (e.g., `Array<int>` to `Array<variant>`).
    - Object types: compatibility is typically by class name or inheritance relationship; `GdObjectType` can include class constraints.
- For "TypeType", which is a type representing another type:
  - e.g. `var N = Node` where `N` is a "TypeType" representing the `Node` type.
  - We do not explicitly model "TypeType" in the type system; instead, we use a `StringName` to represent the type name as the implementation detail.
  - When we detect `some_str_name.new()`, we treat it as a constructor call for the type named by `some_str_name`.


## Serialization & Text Representation
- Each type should export a stable string representation for documentation, diagnostics, and script annotations.
- Packed arrays use compact binary formats when serialized.
- Textual representations aim to be consistent with Godot type names for easier mapping to target languages or the Godot API.
