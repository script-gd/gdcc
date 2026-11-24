package gd.script.gdcc.api.analysis;

/// Cursor-context classification reported with completion results (mirror of the gdparser
/// completion-context kinds, decoupled so the gdparser type never leaks into the public API).
public enum CompletionContextKind {
    /// Cursor completes a member after `receiver.` (dot completion).
    MEMBER_ACCESS,
    /// Cursor sits on a bare identifier prefix (value/name completion).
    IDENTIFIER,
    /// Cursor sits in a type annotation position (`var x: |`, `as |`, `extends |`, ...).
    TYPE_POSITION,
    /// Cursor sits inside a call argument list (no candidates).
    CALL_ARGUMENT,
    /// Context could not be classified; always an empty candidate list.
    UNKNOWN,
    /// Cursor sits in a new parameter-name slot of a function or signal (no candidates).
    PARAMETER_NAME,
    /// Cursor sits in the empty body on the same logical line as a callable header.
    SUITE_START,
    /// Cursor completes a required keyword, such as `in` after an infix `not`.
    EXPECTED_KEYWORD
}
