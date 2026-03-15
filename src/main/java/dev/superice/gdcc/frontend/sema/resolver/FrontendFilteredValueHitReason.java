package dev.superice.gdcc.frontend.sema.resolver;

/// Why a same-name binding was filtered out before the resolver continued to an outer scope.
public enum FrontendFilteredValueHitReason {
    DECLARATION_AFTER_USE_SITE,
    SELF_REFERENCE_IN_INITIALIZER
}
