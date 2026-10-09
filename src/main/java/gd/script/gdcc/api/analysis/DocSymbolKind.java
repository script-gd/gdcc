package gd.script.gdcc.api.analysis;

/// Symbol classification carried by `SymbolDocDescriptor`. The classification is driven by the
/// published declaration model type first and only then by the binding kind, so it stays stable
/// across the different fact tables a cursor position can hit.
public enum DocSymbolKind {
    LOCAL_VARIABLE,
    PARAMETER,
    PROPERTY,
    METHOD,
    SIGNAL,
    CONSTANT,
    ENUM_GROUP,
    ENUM_VALUE,
    UTILITY_FUNCTION,
    TYPE
}
