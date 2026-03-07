package dev.superice.gdcc.exception;

import dev.superice.gdcc.scope.resolver.ScopeMethodResolver;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Internal failure used by `ScopeMethodResolver` to abort deep metadata walks.
///
/// The shared resolver still converts this exception back into its stable
/// `Failed` result protocol at the public boundary, so callers never need to
/// catch it directly. Keeping the type here enforces the repository rule that
/// custom exceptions live under the shared `exception` package.
public final class ScopeMethodResolutionException extends GdccException {
    private final @NotNull ScopeMethodResolver.FailureKind kind;

    public ScopeMethodResolutionException(@NotNull ScopeMethodResolver.FailureKind kind,
                                          @NotNull String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public @NotNull ScopeMethodResolver.FailureKind kind() {
        return kind;
    }
}
