package dev.superice.gdcc.exception;

/// Raised when the API layer cannot find the requested module by normalized ID.
public final class ApiModuleNotFoundException extends GdccException {
    public ApiModuleNotFoundException(String message) {
        super(message);
    }
}
