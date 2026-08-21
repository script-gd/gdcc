package gd.script.gdcc.gdextension;

/// Top-level Godot global constant metadata.
/// These constants are looked up by name from the global value namespace, not through enum owner
/// grouping. Values stay `long` because Godot exports CoreConstants through int64 metadata.
///
/// Instances may also carry compiler-synthesized extreme-value entries that the current API dump
/// does not provide. JSON-provided entries keep priority over those synthesized facts.
public record ExtensionGlobalConstant(
        String name,
        long value,
        boolean isBitfield
) {
}
