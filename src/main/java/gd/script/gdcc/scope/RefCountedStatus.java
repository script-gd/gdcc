package gd.script.gdcc.scope;

/// Tri-state status for whether an Object type is reference-counted.
/// Used to determine if we need to call own_object/release_object or try_own_object/try_release_object.
public enum RefCountedStatus {
    /// The type is definitely reference-counted (e.g., inherits from RefCounted).
    YES,
    /// The type is definitely NOT reference-counted (e.g., Node, Object).
    NO,
    /// We cannot determine the ref-counted status (e.g., unknown engine type or missing class info).
    UNKNOWN
}
