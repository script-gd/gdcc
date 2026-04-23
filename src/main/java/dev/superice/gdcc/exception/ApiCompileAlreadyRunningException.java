package dev.superice.gdcc.exception;

/// Raised when a module already owns an active compile task and the caller starts another one.
public final class ApiCompileAlreadyRunningException extends GdccException {
    public ApiCompileAlreadyRunningException(String message) {
        super(message);
    }
}
