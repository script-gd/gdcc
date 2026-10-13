package gd.script.gdcc.api.analysis;

/// Cursor-context classification reported with completion results (mirror of the gdparser
/// completion-context kinds, decoupled so the gdparser type never leaks into the public API).
/// `CALL_ARGUMENT` is classified but carries no candidates in the minimal V1 ruleset.
public enum CompletionContextKind {
    /// Cursor completes a member after `receiver.` (dot completion).
    MEMBER_ACCESS,
    /// Cursor sits on a bare identifier prefix (value/name completion).
    IDENTIFIER,
    /// Cursor sits in a type annotation position (`var x: |`, `as |`, `extends |`, ...).
    TYPE_POSITION,
    /// Cursor sits inside a call argument list (no candidates in V1).
    CALL_ARGUMENT,
    /// Context could not be classified; always an empty candidate list.
    UNKNOWN
}
