package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Same-generation parser diagnostic and its optional recovery attribution.
///
/// @param code stable parser diagnostic code, or `parse.lowering` if missing or blank
/// @param severity mapped frontend diagnostic severity
/// @param message parser diagnostic message
/// @param range diagnostic byte and line-column range within the source unit
/// @param owner surviving AST node from the same parse, or `null` for a standalone diagnostic
/// @param facet damaged syntax slot, present exactly when `owner` is present
public record FrontendParseRecovery(
        @NotNull String code,
        @NotNull FrontendDiagnosticSeverity severity,
        @NotNull String message,
        @Nullable FrontendRange range,
        @Nullable Node owner,
        @Nullable String facet
) {
    static final String PARSE_INTERNAL_CATEGORY = "parse.internal";

    public FrontendParseRecovery {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if ((owner == null) != (facet == null)) {
            throw new IllegalArgumentException("owner and facet must be both null or both non-null");
        }
    }

    @NotNull FrontendDiagnostic toDiagnostic(@Nullable String sourcePath) {
        return new FrontendDiagnostic(severity, code, message, sourcePath, range);
    }

    /// Checks the active diagnostic index, not just the analysis generation's recorded claim.
    /// A different manager may not contain the claimed error.
    public static @Nullable FrontendDiagnostic findActiveCoveringDiagnostic(
            @NotNull FrontendParseRecovery recovery,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Set<FrontendDiagnostic> activeDiagnostics,
            @Nullable String sourcePath
    ) {
        return findActiveCoveringDiagnostic(
                recovery,
                analysisData,
                activeDiagnostics,
                FrontendDiagnostic.error(recovery.code(), recovery.message(), sourcePath, recovery.range())
        );
    }

    private static @Nullable FrontendDiagnostic findActiveCoveringDiagnostic(
            @NotNull FrontendParseRecovery recovery,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Set<FrontendDiagnostic> activeDiagnostics,
            @NotNull FrontendDiagnostic originalError
    ) {
        if (activeDiagnostics.contains(originalError)) {
            return originalError;
        }
        var claimed = analysisData.claimedParseRecoveries().get(recovery);
        return claimed != null && claimed.severity() == FrontendDiagnosticSeverity.ERROR
                && activeDiagnostics.contains(claimed) ? claimed : null;
    }

    /// Restores blocking parser diagnostics that have no active exact-match coverage.
    public static void ensureUncoveredParseDiagnosticsReported(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        ensureUncoveredParseDiagnosticsReported(
                analysisData,
                diagnosticManager,
                new HashSet<>(diagnosticManager.snapshot().asList())
        );
    }

    /// Reuses a caller's live diagnostic index and updates it as each diagnostic is appended.
    public static void ensureUncoveredParseDiagnosticsReported(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull Set<FrontendDiagnostic> activeDiagnostics
    ) {
        var activeInternalErrors = parseInternalErrorsBySourcePath(activeDiagnostics);
        var previous = analysisData.diagnosticsOrNull();
        var priorInternalErrors = previous == null ? Map.<String, FrontendDiagnostic>of()
                : parseInternalErrorsBySourcePath(previous.asList());
        for (var unit : analysisData.effectiveSourceUnits()) {
            var sourcePath = FrontendDiagnostic.sourcePathText(unit.path());
            if (unit.parseFailed()) {
                if (!activeInternalErrors.containsKey(sourcePath)) {
                    var error = priorInternalErrors.get(sourcePath);
                    if (error == null) {
                        error = FrontendDiagnostic.error(
                                PARSE_INTERNAL_CATEGORY, "Unexpected parser failure.", sourcePath, null
                        );
                    }
                    diagnosticManager.report(error);
                    activeDiagnostics.add(error);
                    activeInternalErrors.put(sourcePath, error);
                }
                continue;
            }
            for (var recovery : unit.parseRecoveries()) {
                if (recovery.severity() != FrontendDiagnosticSeverity.ERROR) {
                    continue;
                }
                var originalError = recovery.toDiagnostic(sourcePath);
                if (findActiveCoveringDiagnostic(recovery, analysisData, activeDiagnostics, originalError) == null) {
                    // Replaying into a different manager must not replace the recorded claim.
                    diagnosticManager.report(originalError);
                    activeDiagnostics.add(originalError);
                }
            }
        }
    }

    private static @NotNull Map<String, FrontendDiagnostic> parseInternalErrorsBySourcePath(
            @NotNull Iterable<FrontendDiagnostic> diagnostics
    ) {
        var errors = new HashMap<String, FrontendDiagnostic>();
        for (var diagnostic : diagnostics) {
            if (diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                    && PARSE_INTERNAL_CATEGORY.equals(diagnostic.category())) {
                errors.putIfAbsent(diagnostic.sourcePath(), diagnostic);
            }
        }
        return errors;
    }
}
