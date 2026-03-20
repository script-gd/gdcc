package dev.superice.gdcc.scope;

/// Value binding kinds that may appear in scope-driven semantic analysis.
///
/// The enum answers "what kind of runtime/value binding did this identifier resolve to?".
/// It is primarily used together with `ScopeValue`.
public enum ScopeValueKind {
    /// A block-local or temporary binding introduced by statements such as assignment, `for`, or `match`.
    LOCAL,
    /// A formal parameter declared by a function, constructor, or lambda.
    PARAMETER,
    /// A captured binding imported from an outer callable scope.
    CAPTURE,
    /// An instance or static property exposed by the current class scope.
    PROPERTY,
    /// A value-side signal binding exposed by the current class scope.
    ///
    /// Signals intentionally stay in the value namespace instead of being modeled as function
    /// overloads. The corresponding `ScopeValue` usually carries a `SignalDef` declaration plus a
    /// `GdSignalType` value type so later frontend stages can still tell "this identifier is a
    /// signal" without guessing.
    SIGNAL,
    /// A compile-time constant binding.
    CONSTANT,
    /// A global engine singleton exposed as a value binding.
    SINGLETON,
    /// A global enum exposed as a value-like symbol in some caller-owned binding tables.
    GLOBAL_ENUM,
    /// A compatibility escape hatch for callers that flatten type-meta results into a unified binding table.
    ///
    /// The core `Scope` protocol still resolves type/meta through the dedicated `resolveTypeMeta(...)`
    /// namespace, so this enum constant should not be read as "type names are part of value lookup".
    TYPE_META,
}
