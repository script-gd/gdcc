package dev.superice.gdcc.exception;

/// Raised when the API layer cannot find the requested compile task by ID, either because that task
/// was never created or because its retention TTL has already expired.
public final class ApiCompileTaskNotFoundException extends GdccException {
    public ApiCompileTaskNotFoundException(String message) {
        super(message);
    }
}
