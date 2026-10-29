package gd.script.gdcc.frontend.sema.analyzer.support;

import org.jetbrains.annotations.NotNull;

/// Capability gate applied by `FrontendConstantEvaluator` while reducing an expression
/// (const plan §4.3).
///
/// The legacy enum pre-pass runs a dedicated policy that keeps its int-only AST whitelist and
/// earlier-member view; the general policy admits the full §5.1–§5.5 surface. A denied
/// capability must produce `Evaluation.Rejected`, never silent evaluation.
public interface EvaluationPolicy {

    /// Whether the evaluator may exercise the given capability for this evaluation.
    boolean permits(@NotNull Capability capability);

    /// Feature-gate keys — one entry per spec clause of the const evaluation whitelist.
    enum Capability {
        /// Integer literal plus unary `+ - ~` and binary `+ - * / % << >> & | ^` under int64
        /// semantics (§5.1).
        INT_ARITHMETIC,
        /// Non-negative integer power `**` via repeated squaring, never `Math.pow` (§5.1).
        INT_POWER,
        /// The `long` minimum-value literal edge (§2.7/§4.3); the enum policy keeps rejecting
        /// it to preserve legacy behavior.
        LONG_MIN_LITERAL,
        /// Float literal plus unary `+ -` and binary `+ - * / **` (§5.1).
        FLOAT_ARITHMETIC,
        /// `bool` literals plus `not`/`and`/`or`/`==`/`!=` (§5.1); `and`/`or` still require
        /// whole-tree validation before only the taken side is computed (§5.2).
        BOOL_OPERATORS,
        /// `String` literals plus `+`/`==`/`!=` on decoded payloads (§5.1).
        STRING_OPERATORS,
        /// Same-exact-type `int`/`float` comparisons `< <= > >=` (§5.1).
        COMPARISON,
        /// The §5.3 constructor whitelist (scalar conversions and controlled builtins).
        CONSTRUCTOR,
        /// Unary `+`/`-`, same-type component `+ - * /`, `*i` `%`, scalar pairs, and geometry
        /// `==`/`!=` on the controlled builtin types (§5.1).
        BUILTIN_OPERATORS,
        /// §5.4 read-only member projections (`x`/`y`/`z`/`w`, `position`/`size`/`end`, ...).
        MEMBER_PROJECTION,
        /// Conditional expressions with a bool condition and same-exact-type branches (§5.2).
        TERNARY,
        /// Any attribute chain step (namespace member read, projection, registered call).
        ATTRIBUTE_ACCESS,
        /// §5.5 registered const member calls dispatched through the context's registry.
        REGISTERED_MEMBER_CALL
    }
}
