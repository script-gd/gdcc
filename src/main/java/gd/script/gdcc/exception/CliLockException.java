package gd.script.gdcc.exception;

/// Thrown when an output-directory or project publish lock is already held by another gdcc run
/// (exit code 2, before any module is created). The locks coordinate CLI processes that adopted
/// the protocol; they cannot constrain the editor addon or other writers.
public class CliLockException extends GdccException {
    public CliLockException(String message) {
        super(message);
    }
}
