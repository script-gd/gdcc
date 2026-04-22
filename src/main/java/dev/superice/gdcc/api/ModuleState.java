package dev.superice.gdcc.api;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

final class ModuleState {
    private final @NotNull String moduleId;
    private final @NotNull String moduleName;
    private final @NotNull CompileOptions compileOptions;
    private final @NotNull Map<String, String> topLevelCanonicalNameMap;
    private final boolean hasLastCompileResult;

    ModuleState(@NotNull String moduleId, @NotNull String moduleName) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName must not be null");
        compileOptions = CompileOptions.defaults();
        topLevelCanonicalNameMap = Map.of();
        hasLastCompileResult = false;
    }

    /// Every module starts with an empty root directory even before the VFS node model lands.
    @NotNull ModuleSnapshot snapshot() {
        return new ModuleSnapshot(
                moduleId,
                moduleName,
                compileOptions,
                topLevelCanonicalNameMap,
                hasLastCompileResult,
                0
        );
    }
}
