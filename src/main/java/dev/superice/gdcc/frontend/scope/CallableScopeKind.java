package dev.superice.gdcc.frontend.scope;

/// Semantic source tags for callable scopes created by the frontend scope analyzer.
///
/// `CallableScope` is reused for several different AST boundaries. The kind is therefore part of
/// the stable protocol rather than something inferred ad hoc from the owning declaration type.
public enum CallableScopeKind {
    /// Scope created for a `func name(...)` declaration.
    FUNCTION_DECLARATION,

    /// Scope created for the `_init(...)` constructor declaration.
    CONSTRUCTOR_DECLARATION,

    /// Scope created for an expression-level `func(...)` lambda.
    LAMBDA_EXPRESSION
}
