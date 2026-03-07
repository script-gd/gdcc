package dev.superice.gdcc.scope;

/// Value binding kinds supported by `Scope` v1.
public enum ScopeValueKind {
    LOCAL,
    PARAMETER,
    CAPTURE,
    PROPERTY,
    CONSTANT,
    SINGLETON,
    GLOBAL_ENUM,
    TYPE_META,
}
