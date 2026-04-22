package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory module registry facade intended for RPC adapters.
///
/// The API package owns remote-facing lifecycle and state orchestration, while frontend/lowering/
/// backend remain the sole compilation fact sources. Step 2 adds virtual-path normalization plus
/// in-memory file/directory CRUD without coupling the facade to any transport framework.
public final class API {
    private final @NotNull Clock clock;
    private final @NotNull ConcurrentHashMap<String, ModuleState> modules = new ConcurrentHashMap<>();

    public API() {
        this(Clock.systemUTC());
    }

    API(@NotNull Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public @NotNull ModuleSnapshot createModule(@NotNull String moduleId, @NotNull String moduleName) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var createdState = new ModuleState(
                normalizedModuleId,
                StringUtil.requireTrimmedNonBlank(moduleName, "moduleName"),
                clock
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

    public @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot createDirectory(
            @NotNull String moduleId,
            @NotNull String path
    ) {
        return requireModuleState(moduleId).createDirectory(VirtualPath.parse(path));
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content
    ) {
        return requireModuleState(moduleId).putFile(VirtualPath.parse(path), content);
    }

    public @NotNull String readFile(@NotNull String moduleId, @NotNull String path) {
        return requireModuleState(moduleId).readFile(VirtualPath.parse(path));
    }

    public @NotNull VfsEntrySnapshot.LinkEntrySnapshot createLink(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull VfsEntrySnapshot.LinkKind linkKind,
            @NotNull String target
    ) {
        return requireModuleState(moduleId).createLink(VirtualPath.parse(path), linkKind, target);
    }

    public @NotNull VfsEntrySnapshot deletePath(@NotNull String moduleId, @NotNull String path, boolean recursive) {
        return requireModuleState(moduleId).deletePath(VirtualPath.parse(path), recursive);
    }

    /// Directory entries are returned in stable lexical order so RPC consumers can diff results.
    public @NotNull List<VfsEntrySnapshot> listDirectory(@NotNull String moduleId, @NotNull String path) {
        return requireModuleState(moduleId).listDirectory(VirtualPath.parse(path));
    }

    public @NotNull VfsEntrySnapshot readEntry(@NotNull String moduleId, @NotNull String path) {
        return requireModuleState(moduleId).readEntry(VirtualPath.parse(path));
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
