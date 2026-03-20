package dev.superice.gdcc.scope;

/// Canonical owner classification shared by scope-level method/property resolution.
///
/// The enum answers "which metadata domain owns the resolved member?" so frontend and backend can
/// describe the same lookup result without each inventing their own owner taxonomy.
public enum ScopeOwnerKind {
    /// The member is declared by a GDCC user script class currently tracked by the compiler.
    GDCC,
    /// The member is declared by an engine class exposed through the GDExtension API metadata.
    ENGINE,
    /// The member is declared by a language builtin or builtin container type.
    BUILTIN
}
