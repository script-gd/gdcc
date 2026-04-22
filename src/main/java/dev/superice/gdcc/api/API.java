package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory module registry facade intended for RPC adapters.
///
/// The API package owns remote-facing lifecycle and state orchestration, while frontend/lowering/
/// backend remain the sole compilation fact sources. Step 1 only stabilizes module lifecycle plus
/// default per-module state; VFS and compile orchestration land in later steps.
public final class API {
    private final @NotNull ConcurrentHashMap<String, ModuleState> modules = new ConcurrentHashMap<>();

    public @NotNull ModuleSnapshot createModule(@NotNull String moduleId, @NotNull String moduleName) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var createdState = new ModuleState(
                normalizedModuleId,
                StringUtil.requireTrimmedNonBlank(moduleName, "moduleName")
        );
        var existingState = modules.putIfAbsent(normalizedModuleId, createdState);
        if (existingState != null) {
            throw new ApiModuleAlreadyExistsException("Module '" + normalizedModuleId + "' already exists");
        }
        return createdState.snapshot();
    }

    public @NotNull ModuleSnapshot getModule(@NotNull String moduleId) {
        return requireModuleState(moduleId).snapshot();
    }

    /// Stable ordering keeps RPC list responses predictable even though storage uses a concurrent map.
    public @NotNull List<ModuleSnapshot> listModules() {
        return modules.values().stream()
                .map(ModuleState::snapshot)
                .sorted(Comparator.comparing(ModuleSnapshot::moduleId))
                .toList();
    }

    public @NotNull ModuleSnapshot deleteModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var removedState = modules.remove(normalizedModuleId);
        if (removedState == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        return removedState.snapshot();
    }

    private @NotNull ModuleState requireModuleState(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var moduleState = modules.get(normalizedModuleId);
        if (moduleState == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        return moduleState;
    }

    private @NotNull String normalizeModuleId(@NotNull String moduleId) {
        return StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
    }
}
