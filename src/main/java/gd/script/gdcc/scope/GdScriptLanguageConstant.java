package gd.script.gdcc.scope;

/// Compiler-synthesized metadata for GDScript language-level constants such as `PI`, `TAU`,
/// `INF`, and `NAN`.
///
/// The Godot engine injects these globals in `GDScriptLanguage::init()`, so they never appear in
/// the GDExtension API dump. The compiler registers them as synthetic facts in [ClassRegistry]
/// instead of loading them from JSON metadata. Values stay `double` because the engine exposes
/// these constants as `float`.
public record GdScriptLanguageConstant(
        String name,
        double value
) {
}
