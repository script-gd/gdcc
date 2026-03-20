package dev.superice.gdcc.scope;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Rich lookup result returned by the restriction-aware `Scope` APIs.
///
/// The result keeps two pieces of information together:
/// - whether a binding exists at the current lookup layer
/// - whether the current restriction allows that binding to be consumed
///
/// This lets callers preserve Godot-style shadowing semantics:
/// - `FOUND_ALLOWED` stops lookup and yields a usable binding
/// - `FOUND_BLOCKED` also stops lookup, but signals that the hit is illegal in the current context
/// - `NOT_FOUND` is the only state that allows recursion into the parent scope
public record ScopeLookupResult<T>(
        @NotNull ScopeLookupStatus status,
        @Nullable T value
) {
    public ScopeLookupResult {
        Objects.requireNonNull(status, "status");
        if (status == ScopeLookupStatus.NOT_FOUND && value != null) {
            throw new IllegalArgumentException("NOT_FOUND result must not carry a value");
        }
        if (status != ScopeLookupStatus.NOT_FOUND && value == null) {
            throw new IllegalArgumentException("Found result must carry a value");
        }
    }

    public static <T> @NotNull ScopeLookupResult<T> foundAllowed(@NotNull T value) {
        return new ScopeLookupResult<>(ScopeLookupStatus.FOUND_ALLOWED, Objects.requireNonNull(value, "value"));
    }

    public static <T> @NotNull ScopeLookupResult<T> foundBlocked(@NotNull T value) {
        return new ScopeLookupResult<>(ScopeLookupStatus.FOUND_BLOCKED, Objects.requireNonNull(value, "value"));
    }

    public static <T> @NotNull ScopeLookupResult<T> notFound() {
        return new ScopeLookupResult<>(ScopeLookupStatus.NOT_FOUND, null);
    }

    public boolean isFound() {
        return status != ScopeLookupStatus.NOT_FOUND;
    }

    public boolean isAllowed() {
        return status == ScopeLookupStatus.FOUND_ALLOWED;
    }

    public boolean isBlocked() {
        return status == ScopeLookupStatus.FOUND_BLOCKED;
    }

    public boolean isNotFound() {
        return status == ScopeLookupStatus.NOT_FOUND;
    }

    public @Nullable T allowedValueOrNull() {
        return isAllowed() ? value : null;
    }

    public @NotNull T requireValue() {
        if (!isFound()) {
            throw new IllegalStateException("Lookup result does not carry a value");
        }
        return Objects.requireNonNull(value, "value");
    }
}
