package dev.superice.gdcc.frontend.sema;

/// Symbol binding categories resolved during frontend semantic analysis.
public enum FrontendBindingKind {
    LOCAL_VAR,
    PARAMETER,
    CAPTURE,
    PROPERTY,
    UTILITY_FUNCTION,
    CONSTANT,
    SINGLETON,
    GLOBAL_ENUM,
    UNKNOWN
}
