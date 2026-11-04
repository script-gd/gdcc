package gd.script.gdcc.exception;

/// Thrown when a Godot `ConfigFile` document (`project.godot`, `.gdextension`) cannot be parsed or
/// a consumed setting carries an unsupported type / override source.
public class GodotConfigException extends GdccException {
    public GodotConfigException(String message) {
        super(message);
    }

    public GodotConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
