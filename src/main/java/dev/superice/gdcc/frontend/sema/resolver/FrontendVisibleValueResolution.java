package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Frontend-only visible-value lookup result.
///
/// The result deliberately preserves more provenance than `ScopeLookupResult`:
/// - `visibleValue` tells the caller which binding won, if any
/// - `filteredHits` keeps same-name bindings that existed in lookup order but were filtered out
/// - `deferredBoundary` seals unsupported domains without pretending the lookup was a normal miss
public record FrontendVisibleValueResolution(
        @NotNull FrontendVisibleValueStatus status,
        @Nullable ScopeValue visibleValue,
        @NotNull List<FrontendFilteredValueHit> filteredHits,
        @Nullable FrontendVisibleValueDeferredBoundary deferredBoundary
) {
    public FrontendVisibleValueResolution {
        Objects.requireNonNull(status, "status must not be null");
        filteredHits = List.copyOf(Objects.requireNonNull(filteredHits, "filteredHits must not be null"));
        if (status == FrontendVisibleValueStatus.FOUND_ALLOWED
                || status == FrontendVisibleValueStatus.FOUND_BLOCKED) {
            Objects.requireNonNull(visibleValue, "visibleValue must not be null for found results");
            if (deferredBoundary != null) {
                throw new IllegalArgumentException("deferredBoundary must be null for found results");
            }
        } else {
            if (visibleValue != null) {
                throw new IllegalArgumentException("visibleValue must be null for non-found results");
            }
            if (status == FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED
                    && deferredBoundary == null) {
                throw new IllegalArgumentException(
                        "deferredBoundary must not be null for DEFERRED_UNSUPPORTED"
                );
            }
            if (status == FrontendVisibleValueStatus.NOT_FOUND && deferredBoundary != null) {
                throw new IllegalArgumentException("deferredBoundary must be null for NOT_FOUND");
            }
        }
    }

    public static @NotNull FrontendVisibleValueResolution foundAllowed(
            @NotNull ScopeValue visibleValue,
            @NotNull List<FrontendFilteredValueHit> filteredHits
    ) {
        return new FrontendVisibleValueResolution(
                FrontendVisibleValueStatus.FOUND_ALLOWED,
                Objects.requireNonNull(visibleValue, "visibleValue must not be null"),
                filteredHits,
                null
        );
    }

    public static @NotNull FrontendVisibleValueResolution foundBlocked(
            @NotNull ScopeValue visibleValue,
            @NotNull List<FrontendFilteredValueHit> filteredHits
    ) {
        return new FrontendVisibleValueResolution(
                FrontendVisibleValueStatus.FOUND_BLOCKED,
                Objects.requireNonNull(visibleValue, "visibleValue must not be null"),
                filteredHits,
                null
        );
    }

    public static @NotNull FrontendVisibleValueResolution notFound(
            @NotNull List<FrontendFilteredValueHit> filteredHits
    ) {
        return new FrontendVisibleValueResolution(
                FrontendVisibleValueStatus.NOT_FOUND,
                null,
                filteredHits,
                null
        );
    }

    public static @NotNull FrontendVisibleValueResolution deferredUnsupported(
            @NotNull FrontendVisibleValueDeferredBoundary deferredBoundary
    ) {
        return new FrontendVisibleValueResolution(
                FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED,
                null,
                List.of(),
                Objects.requireNonNull(deferredBoundary, "deferredBoundary must not be null")
        );
    }

    /// Current executable-body consumers usually only care about the nearest filtered hit.
    public @Nullable FrontendFilteredValueHit primaryFilteredHit() {
        return filteredHits.isEmpty() ? null : filteredHits.getFirst();
    }
}
