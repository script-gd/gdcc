package dev.superice.gdcc.scope;

/// Strict type-meta categories resolved from the dedicated type namespace.
///
/// The enum answers "which family of type symbol was resolved?". This is useful for frontend
/// diagnostics because builtins, engine classes, user script classes, and enums do not always have
/// the same follow-up rules.
public enum ScopeTypeMetaKind {
    /// A language builtin type or a synthesized builtin container type such as `Array[String]`.
    BUILTIN,
    /// A Godot engine class provided by the extension API metadata.
    ENGINE_CLASS,
    /// A GDCC user class declared directly in the scripts currently being compiled.
    GDCC_CLASS,
    /// A global enum name used in type position.
    ///
    /// The current minimal implementation maps this to `int` on the runtime/value side.
    GLOBAL_ENUM
}
