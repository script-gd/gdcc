package dev.superice.gdcc.exception;

/// Raised when the API layer is asked to create a module whose normalized ID already exists.
public final class ApiModuleAlreadyExistsException extends GdccException {
    public ApiModuleAlreadyExistsException(String message) {
        super(message);
    }
}
