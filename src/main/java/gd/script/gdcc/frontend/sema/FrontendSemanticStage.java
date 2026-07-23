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
    VAR_TYPE_POST
}
