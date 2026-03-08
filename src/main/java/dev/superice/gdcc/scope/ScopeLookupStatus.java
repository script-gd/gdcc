package dev.superice.gdcc.scope;

/// Tri-state lookup outcome used by the restriction-aware `Scope` protocol.
///
/// Why `FOUND_BLOCKED` exists:
/// - Godot treats a current-layer hit as shadowing even when `static_context` later rejects it.
/// - Returning only nullable values or empty lists cannot distinguish "not found" from
///   "found here, but illegal in this context".
public enum ScopeLookupStatus {
    /// A binding exists at the current lexical level and is legal in the current restriction.
    FOUND_ALLOWED,
    /// A binding exists at the current lexical level but is blocked by the current restriction.
    FOUND_BLOCKED,
    /// No binding exists at the current lexical level, so lookup may continue to the parent.
    NOT_FOUND
}
