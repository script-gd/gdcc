package dev.superice.gdcc.frontend.sema;

/// Published status for one member-resolution step in body-phase analysis.
public enum FrontendMemberResolutionStatus {
    /// The step resolved to a stable member target and published a stable result type.
    ///
    /// Downstream suffix analysis may continue exact member/call resolution from this step.
    RESOLVED,
    /// A member target exists and won the lookup, but the current semantic context forbids using it.
    ///
    /// This is intentionally distinct from `FAILED`: the hit still exists and still shadows other
    /// names/routes, so callers must not silently retry outer/global alternatives.
    BLOCKED,
    /// The route is part of the supported surface, but the current published analysis state lacks
    /// enough frozen input facts to publish a stable member target/result yet.
    ///
    /// Typical examples include argument typing or other local prerequisites that are not yet
    /// available at the current reduction point.
    DEFERRED,
    /// The step intentionally falls back to runtime-dynamic semantics instead of exact static member
    /// resolution.
    ///
    /// This is not "unknown" and not a hard failure; it means the analyzer has already chosen the
    /// dynamic route and downstream stages should treat the suffix accordingly.
    DYNAMIC,
    /// The route belongs to the supported domain and has reached a stable negative conclusion.
    ///
    /// Typical examples:
    /// - member truly missing on a known hierarchy
    /// - malformed metadata that makes exact resolution impossible
    /// - a statically illegal member route after enough input facts are known
    FAILED,
    /// The analyzer recognized the route/source, but that route is outside the current body-phase
    /// support contract.
    ///
    /// This status is used for fail-closed feature boundaries rather than ordinary semantic failure.
    UNSUPPORTED
}
