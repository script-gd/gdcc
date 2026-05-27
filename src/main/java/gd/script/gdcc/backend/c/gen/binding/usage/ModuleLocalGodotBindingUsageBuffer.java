package gd.script.gdcc.backend.c.gen.binding.usage;

import gd.script.gdcc.backend.c.gen.binding.GodotBindingSymbol;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Function-scope buffer for module-local Godot singleton/constant wrappers.
///
/// Calls emitted through `CBodyBuilder.call*` are checked against the runtime-provided symbol set.
/// A non-provided wrapper must be explicitly recorded by the emitting path;
/// the generated C text scanner remains a verifier, not a source of extra wrappers.
final class ModuleLocalGodotBindingUsageBuffer
        extends AbstractUsageBuffer<String, ModuleLocalGodotBinding> {
    private static final @NotNull ModuleLocalGodotBindingUsageBuffer NO_OP =
            new ModuleLocalGodotBindingUsageBuffer(true, Set.of());

    private final @NotNull Set<String> providedCFunctionNames;
    private final LinkedHashMap<String, ModuleLocalGodotBinding> bindingsByCFunctionName = new LinkedHashMap<>();

    private ModuleLocalGodotBindingUsageBuffer(boolean noOp, @NotNull Set<String> providedCFunctionNames) {
        super(noOp);
        this.providedCFunctionNames = Set.copyOf(providedCFunctionNames);
    }

    static @NotNull ModuleLocalGodotBindingUsageBuffer create(@NotNull Set<String> providedCFunctionNames) {
        return new ModuleLocalGodotBindingUsageBuffer(false, providedCFunctionNames);
    }

    static @NotNull ModuleLocalGodotBindingUsageBuffer noOp() {
        return NO_OP;
    }

    void record(@NotNull ModuleLocalGodotBinding binding) {
        Objects.requireNonNull(binding);
        if (isNoOp() || providedCFunctionNames.contains(binding.symbol().cFunctionName())) {
            return;
        }
        putBinding(binding);
    }

    void recordCall(@NotNull String cFunctionName) {
        Objects.requireNonNull(cFunctionName);
        if (isNoOp() || !cFunctionName.startsWith("godot_") || providedCFunctionNames.contains(cFunctionName)) {
            return;
        }
        if (bindingsByCFunctionName.containsKey(cFunctionName)) {
            return;
        }
        throw new IllegalStateException(
                "Godot binding wrapper '" + cFunctionName
                        + "' is not runtime-provided and was not explicitly registered as module-local"
        );
    }

    @NotNull Map<String, ModuleLocalGodotBinding> snapshot() {
        return snapshotMap();
    }

    private void putBinding(@NotNull ModuleLocalGodotBinding binding) {
        var canonicalKey = canonicalKey(binding.symbol());
        var existingByKey = get(canonicalKey);
        if (existingByKey != null) {
            put(canonicalKey, existingByKey.mergeCompatible(binding));
            bindingsByCFunctionName.put(binding.symbol().cFunctionName(), Objects.requireNonNull(get(canonicalKey)));
            return;
        }
        var existingByName = bindingsByCFunctionName.get(binding.symbol().cFunctionName());
        if (existingByName != null) {
            throw new IllegalStateException(
                    "Godot binding C name conflict for '" + binding.symbol().cFunctionName()
                            + "': " + existingByName.symbol().signatureKey()
                            + " vs " + binding.symbol().signatureKey()
            );
        }
        put(canonicalKey, binding);
        bindingsByCFunctionName.put(binding.symbol().cFunctionName(), binding);
    }

    static @NotNull String canonicalKey(@NotNull GodotBindingSymbol symbol) {
        return symbol.family() + "|" + symbol.owner() + "|" + symbol.name() + "|"
                + symbol.cFunctionName() + "|" + symbol.signatureKey();
    }
}
