package dev.superice.gdcc.api;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Frozen result of one module compile attempt.
///
/// The API layer keeps this result transport-friendly and stage-oriented:
/// - `outcome` tells callers which phase stopped the compile
/// - `compileOptions`, class-name mapping, and source paths freeze the user-visible inputs
/// - `sourcePaths` intentionally expose file display paths rather than raw module VFS paths
/// - frontend diagnostics remain the canonical semantic fact source
/// - backend build details stay summarized as generated-file/artifact paths, mounted VFS links,
///   and build log
public record CompileResult(
        @NotNull Outcome outcome,
        @NotNull CompileOptions compileOptions,
        @NotNull Map<String, String> topLevelCanonicalNameMap,
        @NotNull List<String> sourcePaths,
        @NotNull DiagnosticSnapshot diagnostics,
        @Nullable String failureMessage,
        @NotNull String buildLog,
        @NotNull List<Path> generatedFiles,
        @NotNull List<Path> artifacts,
        @NotNull List<VfsEntrySnapshot.LinkEntrySnapshot> outputLinks
) {
    public CompileResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(compileOptions, "compileOptions must not be null");
        topLevelCanonicalNameMap = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
        )));
        sourcePaths = freezeSourcePaths(sourcePaths);
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        failureMessage = validateFailureMessage(outcome, failureMessage);
        Objects.requireNonNull(buildLog, "buildLog must not be null");
        generatedFiles = freezeHostPaths(generatedFiles, "generatedFiles");
        artifacts = freezeHostPaths(artifacts, "artifacts");
        outputLinks = freezeOutputLinks(outputLinks);
    }

    public boolean success() {
        return outcome == Outcome.SUCCESS;
    }

    public enum Outcome {
        SUCCESS,
        SOURCE_COLLECTION_FAILED,
        CONFIGURATION_FAILED,
        FRONTEND_FAILED,
        BUILD_FAILED
    }

    private static @NotNull List<String> freezeSourcePaths(@NotNull List<String> sourcePaths) {
        var frozen = List.copyOf(Objects.requireNonNull(sourcePaths, "sourcePaths must not be null"));
        for (var sourcePath : frozen) {
            StringUtil.requireTrimmedNonBlank(sourcePath, "sourcePaths element");
        }
        return frozen;
    }

    private static @Nullable String validateFailureMessage(
            @NotNull Outcome outcome,
            @Nullable String failureMessage
    ) {
        if (outcome == Outcome.SUCCESS) {
            if (failureMessage != null) {
                throw new IllegalArgumentException("failureMessage must be null when outcome is SUCCESS");
            }
            return null;
        }
        return StringUtil.requireTrimmedNonBlank(failureMessage, "failureMessage");
    }

    private static @NotNull List<Path> freezeHostPaths(@NotNull List<Path> paths, @NotNull String fieldName) {
        var frozen = List.copyOf(Objects.requireNonNull(paths, fieldName + " must not be null"));
        for (var path : frozen) {
            Objects.requireNonNull(path, fieldName + " must not contain null elements");
        }
        return frozen;
    }

    private static @NotNull List<VfsEntrySnapshot.LinkEntrySnapshot> freezeOutputLinks(
            @NotNull List<VfsEntrySnapshot.LinkEntrySnapshot> outputLinks
    ) {
        var frozen = List.copyOf(Objects.requireNonNull(outputLinks, "outputLinks must not be null"));
        for (var outputLink : frozen) {
            Objects.requireNonNull(outputLink, "outputLinks must not contain null elements");
            if (outputLink.linkKind() != VfsEntrySnapshot.LinkKind.LOCAL) {
                throw new IllegalArgumentException("outputLinks must contain only LOCAL links");
            }
        }
        return frozen;
    }
}
