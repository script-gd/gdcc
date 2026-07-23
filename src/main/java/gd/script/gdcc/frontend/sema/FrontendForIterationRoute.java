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
    /// Float shorthand `for i in n`; reserved until `ceil` semantics and a dedicated helper are locked.
    FLOAT_SHORTHAND,
    /// String character iteration; reserved.
    STRING,
    /// Array element iteration; reserved.
    ARRAY,
    /// Dictionary key iteration; reserved. The iterator is the key type, never the value or pair.
    DICTIONARY_KEYS,
    /// Packed array element iteration; reserved.
    PACKED_ARRAY,
    /// Object custom `_iter_*` protocol; reserved.
    OBJECT_CUSTOM,
    /// Runtime-dispatched Variant iteration for iterables that cannot be statically specialized.
    GENERIC_VARIANT
}
