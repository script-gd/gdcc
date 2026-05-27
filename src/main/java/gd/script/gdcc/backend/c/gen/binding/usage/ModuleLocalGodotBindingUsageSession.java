package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.GodotBindingProvidedSymbols;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/// Module-scope collector for singleton/constant Godot wrappers that still vary per module.
///
/// Runtime-provided symbols are preloaded as a name set. Provided calls are accepted but never
/// emitted into `engine_method_binds.h`; non-provided calls must have an explicit module-local binding.
final class ModuleLocalGodotBindingUsageSession
        extends AbstractUsageSession<String, ModuleLocalGodotBinding, ModuleLocalGodotBindingUsageBuffer> {
    private final @NotNull Set<String> providedCFunctionNames;
    private final LinkedHashMap<String, ModuleLocalGodotBinding> bindingsByCFunctionName = new LinkedHashMap<>();

    ModuleLocalGodotBindingUsageSession(@NotNull Set<String> providedCFunctionNames) {
        this.providedCFunctionNames = Set.copyOf(providedCFunctionNames);
    }

    static @NotNull ModuleLocalGodotBindingUsageSession forRegistry(@NotNull ClassRegistry registry) {
        return new ModuleLocalGodotBindingUsageSession(GodotBindingProvidedSymbols.forRegistry(registry));
    }

    @Override
    @NotNull ModuleLocalGodotBindingUsageBuffer newFunctionBuffer() {
        return ModuleLocalGodotBindingUsageBuffer.create(providedCFunctionNames);
    }

    @Override
    protected void putFromBuffer(@NotNull String key, @NotNull ModuleLocalGodotBinding binding) {
        if (providedCFunctionNames.contains(binding.symbol().cFunctionName())) {
            return;
        }
        var existingByKey = getEntry(key);
        if (existingByKey != null) {
            var merged = putEntry(key, existingByKey.mergeCompatible(binding));
            bindingsByCFunctionName.put(binding.symbol().cFunctionName(), merged);
        } else {
            var existingByName = bindingsByCFunctionName.get(binding.symbol().cFunctionName());
            if (existingByName != null) {
                throw new IllegalStateException(
                        "Godot binding C name conflict for '" + binding.symbol().cFunctionName()
                                + "': " + existingByName.symbol().signatureKey()
                                + " vs " + binding.symbol().signatureKey()
                );
            }
            putEntry(key, binding);
            bindingsByCFunctionName.put(binding.symbol().cFunctionName(), binding);
        }
    }

    @NotNull List<ModuleLocalGodotBinding> snapshot() {
        return snapshotValues();
    }

    @NotNull Set<String> providedCFunctionNames() {
        return providedCFunctionNames;
    }

    @NotNull Set<String> moduleLocalCFunctionNames() {
        return Set.copyOf(bindingsByCFunctionName.keySet());
    }
}
