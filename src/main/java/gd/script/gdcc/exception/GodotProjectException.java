package gd.script.gdcc.exception;

/// Thrown when project-mode CLI input preparation fails: an invalid `project.godot` entry, an
/// illegal `-o` work directory, a source path that escapes the project boundary, or a source that
/// cannot be read/verified. Always a usage-level failure (exit code 2), never a compile failure.
public class GodotProjectException extends GdccException {
    public GodotProjectException(String message) {
        super(message);
    }

    public GodotProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
