package dev.superice.gdcc.exception;

/// Raised when virtual-link resolution re-enters an already active link path.
public final class ApiLinkCycleException extends GdccException {
    public ApiLinkCycleException(String message) {
        super(message);
    }
}
