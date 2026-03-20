package dev.superice.gdcc.frontend.scope;

/// Semantic source tags for block scopes created by the frontend scope analyzer.
///
/// Several distinct AST situations lower to the same `BlockScope` implementation. Keeping the
/// source kind explicit makes tests, debugging, and later binder passes independent from AST
/// runtime-type guessing.
public enum BlockScopeKind {
    /// Ordinary standalone `{ ... }`-style block statement.
    BLOCK_STATEMENT,

    /// Body block owned by a `FunctionDeclaration`.
    FUNCTION_BODY,

    /// Body block owned by a `ConstructorDeclaration`.
    CONSTRUCTOR_BODY,

    /// Body block owned by a `LambdaExpression`.
    LAMBDA_BODY,

    /// Main body block of an `IfStatement`.
    IF_BODY,

    /// Body block of one `ElifClause`.
    ELIF_BODY,

    /// Nullable `else` branch body of an `IfStatement`.
    ELSE_BODY,

    /// Loop body of a `WhileStatement`.
    WHILE_BODY,

    /// Loop body of a `ForStatement`.
    FOR_BODY,

    /// Branch body owned by a `MatchSection`.
    MATCH_SECTION_BODY
}
