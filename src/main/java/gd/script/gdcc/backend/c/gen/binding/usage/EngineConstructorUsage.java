package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.GodotBindingSupport;
import org.jetbrains.annotations.NotNull;

/// Module-local engine constructor wrapper required by a successfully rendered function body.
public record EngineConstructorUsage(
        @NotNull String className,
        @NotNull String cIdentifier,
        @NotNull String escapedClassName
) {
    static @NotNull EngineConstructorUsage fromClassName(@NotNull String className) {
        return new EngineConstructorUsage(
                className,
                GodotBindingSupport.cIdentifier(className),
                GodotBindingSupport.escapeCString(className)
        );
    }
}
