package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One same-name binding that existed in lookup order but was filtered by frontend visibility rules.
///
/// The resolver keeps both the `ScopeValue` and the owning lexical `Scope` so diagnostics and
/// downstream consumers can explain not just what was filtered, but also which layer originally
/// contributed that hit.
public record FrontendFilteredValueHit(
        @NotNull ScopeValue value,
        @NotNull Scope owningScope,
        @NotNull FrontendFilteredValueHitReason reason
) {
    public FrontendFilteredValueHit {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(owningScope, "owningScope must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
