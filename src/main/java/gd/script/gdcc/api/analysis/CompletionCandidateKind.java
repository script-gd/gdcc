package gd.script.gdcc.api.analysis;

/// Category of one completion candidate; it describes the suggested text without exposing
/// an internal parser or semantic model object.
public enum CompletionCandidateKind {
    /// Instance or static property (engine/builtin/GDCC class member).
    PROPERTY,
    /// Callable candidate: class methods, utility functions and GDScript language functions.
    METHOD,
    /// Value candidate: locals, parameters, captures, signals, constants and enum entries.
    VALUE,
    /// Type candidate: engine classes, builtin types, project global class names, inner classes.
    TYPE,
    /// Syntax keyword with no type or callable signature.
    KEYWORD
}
