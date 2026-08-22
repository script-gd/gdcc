package gd.script.gdcc.frontend.sema;

/// Pattern route classification for one expression in a `match` section pattern list.
///
/// The route is a pure structural fact published by the match pattern resolution owner and consumed
/// by type-check, compile gate and CFG builder so none of them re-derives pattern semantics from
/// the AST. Classification is centralized in `FrontendMatchSupport`; whether a route is
/// compile-ready is expressed separately by `FrontendMatchSupport.isRouteLoweringReady`, whose
/// ready set grows monotonically. All six routes are currently ready. Routes without lowering
/// readiness stay classified here and are blocked at the compile gate as route-not-ready.
public enum FrontendMatchPatternRoute {
    /// `_` wildcard: matches any subject. Recognized by identifier name only, and only inside the
    /// match pattern recursion context; `_` in ordinary expressions stays a normal identifier.
    WILDCARD,
    /// `var x` binding: matches unconditionally and binds the (sub)value to a new section-local
    /// variable. Only `PatternBindingExpression` nodes are bindings; a bare identifier is not.
    BINDING,
    /// Literal pattern (int / float / String / StringName / bool / null).
    LITERAL,
    /// Any non-literal expression pattern (bare identifier, attribute chain, call, binary op,
    /// ...). Any evaluable expression is a legal pattern (gdcc deliberately extends Godot's
    /// shape whitelist); unresolvable expressions fail in the ordinary expression
    /// resolution pipeline, never here. Constantness only selects the lowering sub-mode
    /// (constant vs runtime comparison), never legality.
    EXPRESSION,
    /// Array pattern `[p0, p1, ..]`; elements are recursively classified sub-patterns.
    ARRAY,
    /// Dictionary pattern `{"k": p, ..}`; entry values are recursively classified sub-patterns,
    /// while entry keys belong to the constant-expression domain.
    DICTIONARY
}
