package gd.script.gdcc.api.analysis;

/// Documentation namespace a symbol belongs to (plan §2.4 `documentationAt`). These are
/// documentation-facing names, not `ClassDef` names — `@GlobalScope` and `@GDScript` denote the
/// corresponding Godot documentation pages, and the namespace decides URL routing in the editor
/// adapter layer.
public enum DocNamespace {
    /// User GDScript code analyzed by GDCC; descriptors carry source positions.
    GDCC,
    /// Engine (GDExtension object) classes.
    ENGINE,
    /// Built-in variant types (`Vector2`, `String`, ...).
    BUILTIN,
    /// Godot `@GlobalScope` page: dump-sourced utility functions, global constants and global
    /// enums.
    GLOBAL_SCOPE,
    /// Godot `@GDScript` page: registry-synthesized language functions (`len`, `range`, ...)
    /// and language constants (`PI`, `TAU`, `INF`, `NAN`).
    GDSCRIPT
}
