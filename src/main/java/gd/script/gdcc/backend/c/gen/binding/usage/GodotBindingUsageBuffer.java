package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

/// Function-scope usage buffer for all generated Godot binding surfaces.
///
/// A successful body render commits this buffer into `GodotBindingUsageSession`; failed renders
/// discard it so no engine helper or module-local wrapper leaks into later generated headers.
public final class GodotBindingUsageBuffer {
    private static final @NotNull GodotBindingUsageBuffer NO_OP = new GodotBindingUsageBuffer(
            EngineMethodUsageBuffer.noOp(),
            EngineConstructorUsageBuffer.noOp(),
            ModuleLocalGodotBindingUsageBuffer.noOp()
    );

    private final @NotNull EngineMethodUsageBuffer engineMethods;
    private final @NotNull EngineConstructorUsageBuffer engineConstructors;
    private final @NotNull ModuleLocalGodotBindingUsageBuffer moduleLocalBindings;

    GodotBindingUsageBuffer(
            @NotNull EngineMethodUsageBuffer engineMethods,
            @NotNull EngineConstructorUsageBuffer engineConstructors,
            @NotNull ModuleLocalGodotBindingUsageBuffer moduleLocalBindings
    ) {
        this.engineMethods = engineMethods;
        this.engineConstructors = engineConstructors;
        this.moduleLocalBindings = moduleLocalBindings;
    }

    public static @NotNull GodotBindingUsageBuffer noOp() {
        return NO_OP;
    }

    public void recordEngineMethodCall(@NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        engineMethods.record(resolved);
    }

    public void recordEngineConstructor(@NotNull GdObjectType constructedType) {
        engineConstructors.record(constructedType);
    }

    public void recordModuleLocalGodotBinding(@NotNull ModuleLocalGodotBinding binding) {
        moduleLocalBindings.record(binding);
    }

    public void recordGodotCall(@NotNull String cFunctionName) {
        moduleLocalBindings.recordCall(cFunctionName);
    }

    @NotNull EngineMethodUsageBuffer engineMethods() {
        return engineMethods;
    }

    @NotNull EngineConstructorUsageBuffer engineConstructors() {
        return engineConstructors;
    }

    @NotNull ModuleLocalGodotBindingUsageBuffer moduleLocalBindings() {
        return moduleLocalBindings;
    }
}
