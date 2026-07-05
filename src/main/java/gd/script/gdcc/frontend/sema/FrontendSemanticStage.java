package gd.script.gdcc.frontend.sema;

/// Segment-local semantic stages that may publish incremental frontend facts.
public enum FrontendSemanticStage {
    TOP_BINDING,
    LOCAL_TYPE_STABILIZATION,
    CHAIN_BINDING,
    EXPR_TYPE,
    VAR_TYPE_POST
}
