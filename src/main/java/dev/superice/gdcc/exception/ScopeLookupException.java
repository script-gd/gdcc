package dev.superice.gdcc.exception;

/// Internal failure raised when frontend scope metadata is malformed.
///
/// This is used for invariant violations such as inheritance cycles during class-scope member walks.
/// Public scope lookup still communicates ordinary misses and restriction blocks through
/// `ScopeLookupResult`; this exception is reserved for hard metadata corruption.
public final class ScopeLookupException extends GdccException {
    public ScopeLookupException(String message) {
        super(message);
    }
}
