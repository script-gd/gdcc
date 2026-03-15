package dev.superice.gdcc.frontend.sema.resolver;

/// Semantic lookup domains understood by `FrontendVisibleValueResolver`.
///
/// Only `EXECUTABLE_BODY` is implemented in phase 1. The other domains are modeled now so the
/// request/result contract can stay stable when later phases stop returning deferred boundaries.
public enum FrontendVisibleValueDomain {
    EXECUTABLE_BODY,
    PARAMETER_DEFAULT,
    LAMBDA_SUBTREE,
    BLOCK_LOCAL_CONST_SUBTREE,
    FOR_SUBTREE,
    MATCH_SUBTREE,
    UNKNOWN_OR_SKIPPED_SUBTREE
}
