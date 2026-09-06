package gd.script.gdcc.api;

import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Frozen result of one synchronous module analysis attempt.
///
/// The result mirrors the transport-friendly shape of `CompileResult` but stays independent from
/// the compile surface: analysis has no build log, generated files, artifacts, or output links.
/// `outcome` reports whether the analysis pipeline itself ran to completion; code health is
/// reported separately through `diagnostics`, which may contain errors even when the outcome is
/// `COMPLETED`.
public record AnalysisResult(
        @NotNull Outcome outcome,
        @NotNull AnalyzeOptions analyzeOptions,
        @NotNull GodotVersion godotVersion,
        @NotNull Map<String, String> topLevelCanonicalNameMap,
        @NotNull List<String> sourcePaths,
        @NotNull DiagnosticSnapshot diagnostics,
        @Nullable String failureMessage,
        @NotNull LoweringStatus loweringStatus
) {
    public AnalysisResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(analyzeOptions, "analyzeOptions must not be null");
        Objects.requireNonNull(godotVersion, "godotVersion must not be null");
        topLevelCanonicalNameMap = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
        )));
        sourcePaths = freezeSourcePaths(sourcePaths);
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        failureMessage = validateFailureMessage(outcome, failureMessage);
        validateLoweringStatus(outcome, analyzeOptions, loweringStatus);
    }

    /// Returns whether the analysis pipeline ran to completion. This is intentionally not a
    /// code-health answer: a completed analysis can still report errors through `diagnostics`.
    public boolean completed() {
        return outcome == Outcome.COMPLETED;
    }

    /// Returns whether the collected diagnostics already contain an error.
    public boolean hasErrors() {
        return diagnostics.hasErrors();
    }

    public enum Outcome {
        /// The parse/analyze pipeline (and lowering, when requested) ran to completion.
        COMPLETED,
        /// Module VFS source collection failed before parsing, for example on broken virtual links.
        SOURCE_COLLECTION_FAILED,
        /// Required compiler metadata such as the Godot extension API could not be loaded.
        INTERNAL_FAILED
    }

    /// Answers whether the module can currently lower to LIR. Only meaningful when the request
    /// opted in through `AnalyzeOptions.includeLowering()`; `SUCCEEDED` requires a published LIR
    /// module and diagnostics without errors.
    public enum LoweringStatus {
        NOT_REQUESTED,
        SUCCEEDED,
        FAILED
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
        if (outcome == Outcome.COMPLETED) {
            if (failureMessage != null) {
                throw new IllegalArgumentException("failureMessage must be null when outcome is COMPLETED");
            }
            return null;
        }
        return StringUtil.requireTrimmedNonBlank(failureMessage, "failureMessage");
    }

    private static @NotNull LoweringStatus validateLoweringStatus(
            @NotNull Outcome outcome,
            @NotNull AnalyzeOptions analyzeOptions,
            @NotNull LoweringStatus loweringStatus
    ) {
        Objects.requireNonNull(loweringStatus, "loweringStatus must not be null");
        if (!analyzeOptions.includeLowering() && loweringStatus != LoweringStatus.NOT_REQUESTED) {
            throw new IllegalArgumentException("loweringStatus must be NOT_REQUESTED when includeLowering is false");
        }
        if (analyzeOptions.includeLowering() && loweringStatus == LoweringStatus.NOT_REQUESTED) {
            throw new IllegalArgumentException("loweringStatus must not be NOT_REQUESTED when includeLowering is true");
        }
        if (outcome != Outcome.COMPLETED && loweringStatus == LoweringStatus.SUCCEEDED) {
            throw new IllegalArgumentException("loweringStatus cannot be SUCCEEDED when analysis did not complete");
        }
        return loweringStatus;
    }
}
