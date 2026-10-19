package gd.script.gdcc.backend;

import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

/// @param sourceFileFacts Per-source-file caller-facing facts keyed by normalized logical-path text
///                        (`path.toString()` with `/` separators, matching `LirClassDef.sourceFile`).
///                        Empty for hand-built LIR fixtures and phases that never emit class metadata.
public record CodegenContext(
        @NotNull ProjectInfo projectInfo,
        @NotNull ClassRegistry classRegistry,
        boolean strictMode,
        @NotNull Map<String, SourceFileFacts> sourceFileFacts
) {
    public CodegenContext {
        Objects.requireNonNull(projectInfo, "projectInfo must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        sourceFileFacts = Map.copyOf(Objects.requireNonNull(sourceFileFacts, "sourceFileFacts must not be null"));
    }

    public CodegenContext(@NotNull ProjectInfo projectInfo,
                          @NotNull ClassRegistry classRegistry) {
        this(projectInfo, classRegistry, false, Map.of());
    }

    public CodegenContext(@NotNull ProjectInfo projectInfo,
                          @NotNull ClassRegistry classRegistry,
                          boolean strictMode) {
        this(projectInfo, classRegistry, strictMode, Map.of());
    }
}
