package dev.superice.gdcc.exception;

/// Raised when a virtual link resolves to a missing target path inside the module VFS.
public final class ApiBrokenLinkException extends GdccException {
    public ApiBrokenLinkException(String message) {
        super(message);
    }
}
