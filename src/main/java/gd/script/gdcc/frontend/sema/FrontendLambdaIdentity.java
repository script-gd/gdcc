package gd.script.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Identity payload of one source lambda (contract: `hot_reload_implementation_plan.md` §5.11):
/// the ordinal within the outermost named function and the normalized call-site context
/// descriptor (never null — the `stmt(...)` fallback always applies; NULL contexts exist only
/// for backend-synthesized standalone identities). Published into
/// `FrontendAnalysisData.lambdaIdentities()` by `FrontendLambdaIdentityAnalyzer` ahead of suite
/// resolution.
public record FrontendLambdaIdentity(int ordinal, @NotNull String callSiteContext) {
    public FrontendLambdaIdentity {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0: " + ordinal);
        }
        Objects.requireNonNull(callSiteContext, "callSiteContext must not be null");
        if (callSiteContext.isBlank()) {
            throw new IllegalArgumentException("callSiteContext must not be blank");
        }
    }
}
