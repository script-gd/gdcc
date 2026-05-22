package gd.script.gdcc.gdextension;

/// Godot exposes extension API enum values as 64-bit Variant::INT/CoreConstants values.
public record ExtensionEnumValue(
        String name,
        long value
) {
}
