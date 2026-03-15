package dev.superice.gdcc.frontend.sema.resolver;

/// Why the resolver refused to answer with a normal visible binding result.
public enum FrontendVisibleValueDeferredReason {
    UNSUPPORTED_DOMAIN,
    MISSING_SCOPE_OR_SKIPPED_SUBTREE,
    VARIABLE_INVENTORY_NOT_PUBLISHED
}
