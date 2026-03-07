package dev.superice.gdcc.scope.resolver;

/// Default-argument materialization strategy attached to shared method metadata.
///
/// The resolver only classifies *what kind of default source exists*.
/// Frontend/backend still decide how to consume it:
/// - literal defaults may materialize through helper calls or frontend constants
/// - function defaults may need owner-aware validation and invocation
public enum ScopeDefaultArgKind {
    NONE,
    LITERAL,
    FUNCTION
}
