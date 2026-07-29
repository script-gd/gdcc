package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.GodotBindingSupport;
import org.jetbrains.annotations.NotNull;

/// Module-local engine constructor wrapper required by a successfully rendered function body.
///
/// `needsRefCountedInit` is true when the engine class is a definite `RefCounted` descendant.
/// In that case the generated `godot_new_*` wrapper must call `gdcc_ref_counted_init_raw` so the
/// returned raw pointer is already OWNED at refcount=1 (aligned with GDCC class create paths).
public record EngineConstructorUsage(
        @NotNull String className,
        @NotNull String cIdentifier,
        @NotNull String escapedClassName,
        boolean needsRefCountedInit
) {
    static @NotNull EngineConstructorUsage fromClassName(@NotNull String className, boolean needsRefCountedInit) {
        return new EngineConstructorUsage(
                className,
                GodotBindingSupport.cIdentifier(className),
                GodotBindingSupport.escapeCString(className),
                needsRefCountedInit
        );
    }
}
