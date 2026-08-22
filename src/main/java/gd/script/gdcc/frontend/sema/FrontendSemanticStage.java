package gd.script.gdcc.frontend.sema;

/// Segment-local semantic stages that may publish incremental frontend facts.
public enum FrontendSemanticStage {
    TOP_BINDING,
    LOCAL_TYPE_STABILIZATION,
    CHAIN_BINDING,
    EXPR_TYPE,
    /// For-in iteration resolution: publishes the `FrontendForIterationPlan` and the restricted
    /// iterator slot refinement keyed by the owning `ForStatement`. Ordered after expression typing
    /// (it consumes iterable/argument typed facts) and before var-type-post (which publishes the
    /// final source-facing iterator slot type).
    FOR_ITERATION_RESOLUTION,
    /// Match pattern resolution: publishes the `FrontendMatchPlan` and the restricted top-level
    /// bind slot refinement keyed by `PatternBindingExpression`. Ordered after expression typing
    /// (it consumes the subject typed fact) and before var-type-post (which publishes source-facing
    /// bind slot types). Distinct `order()` from `FOR_ITERATION_RESOLUTION` so a suite that contains
    /// both `for` and `match` can export both stages in one transaction.
    MATCH_PATTERN_RESOLUTION,
    VAR_TYPE_POST,
    /// Lambda resolution: publishes the first complete `FrontendLambdaPlan` (declaration-site
    /// capture types filled, `capturesSelf` aligned) keyed by the `LambdaExpression`. The owner is
    /// the lambda's own nested suite resolution; the plan exports with the lambda's independent
    /// callable batch before the enclosing statement's expr-type owner could publish a callable
    /// type for the same node.
    LAMBDA_RESOLUTION
}
