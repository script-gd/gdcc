package dev.superice.gdcc.frontend.sema.resolver;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Frontend-only boundary record describing which semantic domain was deferred and why.
public record FrontendVisibleValueDeferredBoundary(
        @NotNull FrontendVisibleValueDomain domain,
        @NotNull FrontendVisibleValueDeferredReason reason
) {
    public FrontendVisibleValueDeferredBoundary {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
