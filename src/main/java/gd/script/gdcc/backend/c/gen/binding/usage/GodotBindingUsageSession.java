package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/// Module-scope API for all Godot binding usage collected while rendering generated C.
///
/// The generator owns one session per `CCodegen.generate()` call. Each function/property body and
/// template-level facade recorder gets a fresh buffer and commits only after rendering succeeds,
/// keeping the generated header tied to successful `entry.c` output rather than speculative code paths.
public final class GodotBindingUsageSession {
    private final @NotNull EngineMethodUsageSession engineMethods = new EngineMethodUsageSession();
    private final @NotNull EngineConstructorUsageSession engineConstructors = new EngineConstructorUsageSession();
    private final @NotNull ModuleLocalGodotBindingUsageSession moduleLocalBindings;

    public GodotBindingUsageSession(@NotNull Set<String> providedCFunctionNames) {
        this(new ModuleLocalGodotBindingUsageSession(providedCFunctionNames));
    }

    private GodotBindingUsageSession(@NotNull ModuleLocalGodotBindingUsageSession moduleLocalBindings) {
        this.moduleLocalBindings = moduleLocalBindings;
    }

    public static @NotNull GodotBindingUsageSession forRegistry(@NotNull ClassRegistry registry) {
        return new GodotBindingUsageSession(ModuleLocalGodotBindingUsageSession.forRegistry(registry));
    }

    public @NotNull GodotBindingUsageBuffer newFunctionBuffer() {
        return new GodotBindingUsageBuffer(
                engineMethods.newFunctionBuffer(),
                engineConstructors.newFunctionBuffer(),
                moduleLocalBindings.newFunctionBuffer()
        );
    }

    public void commit(@NotNull GodotBindingUsageBuffer buffer) {
        engineMethods.commit(buffer.engineMethods());
        engineConstructors.commit(buffer.engineConstructors());
        moduleLocalBindings.commit(buffer.moduleLocalBindings());
    }

    public @NotNull List<BackendMethodCallResolver.ResolvedMethodCall> engineMethods() {
        return engineMethods.snapshot();
    }

    public @NotNull List<EngineConstructorUsage> engineConstructors() {
        return engineConstructors.snapshot();
    }

    public @NotNull List<ModuleLocalGodotBinding> moduleLocalBindings() {
        return moduleLocalBindings.snapshot();
    }

    public @NotNull Set<String> providedCFunctionNames() {
        return moduleLocalBindings.providedCFunctionNames();
    }

    public @NotNull Set<String> moduleLocalCFunctionNames() {
        return moduleLocalBindings.moduleLocalCFunctionNames();
    }
}
