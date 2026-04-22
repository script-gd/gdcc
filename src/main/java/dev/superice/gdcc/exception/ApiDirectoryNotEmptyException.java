package dev.superice.gdcc.exception;

/// Raised when RPC-side VFS deletion tries to remove a non-empty directory without recursion.
public final class ApiDirectoryNotEmptyException extends GdccException {
    public ApiDirectoryNotEmptyException(String message) {
        super(message);
    }
}
