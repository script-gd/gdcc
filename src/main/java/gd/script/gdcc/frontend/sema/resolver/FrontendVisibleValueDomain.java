package gd.script.gdcc.frontend.sema.resolver;

/// Semantic lookup domains understood by `FrontendVisibleValueResolver`.
///
/// `EXECUTABLE_BODY` and the parameter-default island (`PARAMETER_DEFAULT`) resolve normally — the
/// island keeps its own visibility restrictions inside the resolver (callable-local hits stop
/// blocked at the current layer). The other domains remain explicit so callers can report deferred
/// boundaries without forcing the resolver to infer intent purely from AST shape.
public enum FrontendVisibleValueDomain {
    EXECUTABLE_BODY,
    PARAMETER_DEFAULT,
    LAMBDA_SUBTREE,
    BLOCK_LOCAL_CONST_SUBTREE,
    UNKNOWN_OR_SKIPPED_SUBTREE
}
