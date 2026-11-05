package gd.script.gdcc.exception;

/// Thrown when publishing a successful compile result into a project's `bin/` fails after the
/// compile itself already succeeded (exit code 1). Kept distinct from [GodotProjectException],
/// which marks pre-module usage failures (exit code 2) — the distinction preserves the original
/// installation untouched on publish failures.
public class GdccPublishException extends GdccException {
    public GdccPublishException(String message) {
        super(message);
    }

    public GdccPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
