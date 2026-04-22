package dev.superice.gdcc.exception;

/// Raised when the API layer cannot resolve the requested normalized VFS path inside a module.
public final class ApiPathNotFoundException extends GdccException {
    public ApiPathNotFoundException(String message) {
        super(message);
    }
}
