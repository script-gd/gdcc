package gd.script.gdcc.frontend.sema;

/// Iteration route classification for a `for iterator[: Type] in expr` statement.
///
/// The route is a pure semantic fact published by the for-iteration resolution owner and consumed by
/// type-check, compile gate and CFG builder so none of them re-derives iterable semantics from the
/// AST. Whether a route is compile-ready is expressed separately by
/// `gd.script.gdcc.frontend.lowering.ForLoweringContractRegistry`; routes without a registered
/// lowering contract stay classified here and are blocked at the compile gate as route-not-ready.
public enum FrontendForIterationRoute {
    /// Bare `range(...)` call in iterable position; element type is `int`.
    RANGE_CALL,
    /// Integer shorthand `for i in n`, iterating `0..n`; element type is `int`.
    INT_SHORTHAND,
    /// Float shorthand `for i in n`, iterating `0.0, 1.0, ...` while `i < n`; element type is `float`.
    FLOAT_SHORTHAND,
    /// String character iteration.
    STRING,
    /// Array element iteration.
    ARRAY,
    /// Dictionary key iteration. The iterator is the key type, never the value or pair.
    DICTIONARY_KEYS,
    /// PackedByteArray element iteration.
    PACKED_BYTE_ARRAY,
    /// PackedInt32Array element iteration.
    PACKED_INT32_ARRAY,
    /// PackedInt64Array element iteration.
    PACKED_INT64_ARRAY,
    /// PackedFloat32Array element iteration.
    PACKED_FLOAT32_ARRAY,
    /// PackedFloat64Array element iteration.
    PACKED_FLOAT64_ARRAY,
    /// PackedStringArray element iteration.
    PACKED_STRING_ARRAY,
    /// PackedVector2Array element iteration.
    PACKED_VECTOR2_ARRAY,
    /// PackedVector3Array element iteration.
    PACKED_VECTOR3_ARRAY,
    /// PackedVector4Array element iteration.
    PACKED_VECTOR4_ARRAY,
    /// PackedColorArray element iteration.
    PACKED_COLOR_ARRAY,
    /// Object custom `_iter_*` protocol; reserved.
    OBJECT_CUSTOM,
    /// Runtime-dispatched Variant iteration for iterables that cannot be statically specialized.
    GENERIC_VARIANT
}
