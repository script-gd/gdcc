package gd.script.gdcc.api.analysis;

/// Category of one completion candidate (`frontend_lsp_foundation_implementation.md` §2.5).
/// The LSP layer maps these onto wire-level completion item kinds; no internal model object
/// is serialized into candidates.
public enum CompletionCandidateKind {
    /// Instance or static property (engine/builtin/GDCC class member).
    PROPERTY,
    /// Callable candidate: class methods, utility functions and GDScript language functions.
    METHOD,
    /// Value candidate: locals, parameters, captures, signals, constants and enum entries.
    VALUE,
    /// Type candidate: engine classes, builtin types, project global class names, inner classes.
    TYPE
}
