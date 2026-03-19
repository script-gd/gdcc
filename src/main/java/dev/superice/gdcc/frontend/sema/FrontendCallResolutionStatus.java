package dev.superice.gdcc.frontend.sema;

/// Published status for one call-resolution step in body-phase analysis.
public enum FrontendCallResolutionStatus {
    /// The step resolved to one stable callable route and published a stable return type.
    ///
    /// Later stages may treat the call result as an exact typed receiver for downstream suffixes.
    RESOLVED,
    /// A callable target exists and won the current route, but the present semantic context forbids
    /// using it as written.
    ///
    /// This keeps the winner visible for diagnostics/shadowing instead of collapsing it into a miss.
    BLOCKED,
    /// The call route is part of the supported surface, but the current published analysis state
    /// still lacks enough frozen input facts to choose and publish one stable callable.
    ///
    /// Typical examples include not-yet-stable argument types during local body-phase alternation.
    DEFERRED,
    /// The analyzer has explicitly chosen a runtime-dynamic fallback route for this call.
    ///
    /// This is different from `DEFERRED`: the route decision has already been made, but it is a
    /// dynamic/runtime route instead of an exact static callable.
    DYNAMIC,
    /// The call belongs to the supported domain and the analyzer reached a stable negative result.
    ///
    /// Examples include no applicable overload, a statically illegal non-static/static mismatch, or
    /// other exact-call routes that definitively failed.
    FAILED,
    /// The analyzer recognized the call/source/route but deliberately sealed it because it falls
    /// outside the current body-phase support boundary.
    ///
    /// This is a feature-boundary result, not a normal semantic miss.
    UNSUPPORTED
}
