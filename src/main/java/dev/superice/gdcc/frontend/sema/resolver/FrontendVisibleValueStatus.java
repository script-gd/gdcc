package dev.superice.gdcc.frontend.sema.resolver;

/// Public status returned by `FrontendVisibleValueResolver`.
///
/// The enum stays frontend-specific because declaration-order filtering and deferred-domain
/// sealing are not part of the shared `ScopeLookupResult` contract.
public enum FrontendVisibleValueStatus {
    FOUND_ALLOWED,
    FOUND_BLOCKED,
    NOT_FOUND,
    DEFERRED_UNSUPPORTED
}
