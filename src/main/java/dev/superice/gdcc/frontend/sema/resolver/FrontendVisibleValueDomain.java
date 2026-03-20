package dev.superice.gdcc.frontend.sema.resolver;

/// Semantic lookup domains understood by `FrontendVisibleValueResolver`.
///
/// `EXECUTABLE_BODY` is the only domain that currently resolves normally. The other domains remain
/// explicit so callers can report deferred boundaries without forcing the resolver to infer intent
/// purely from AST shape.
public enum FrontendVisibleValueDomain {
    EXECUTABLE_BODY,
    PARAMETER_DEFAULT,
    LAMBDA_SUBTREE,
    BLOCK_LOCAL_CONST_SUBTREE,
    FOR_SUBTREE,
    MATCH_SUBTREE,
    UNKNOWN_OR_SKIPPED_SUBTREE
}
