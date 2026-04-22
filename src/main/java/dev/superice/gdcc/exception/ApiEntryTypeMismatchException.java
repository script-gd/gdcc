package dev.superice.gdcc.exception;

/// Raised when a VFS operation expects a file or directory but encounters the other entry kind.
public final class ApiEntryTypeMismatchException extends GdccException {
    public ApiEntryTypeMismatchException(String message) {
        super(message);
    }
}
