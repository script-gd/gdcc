package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.List;

/// Basic frontend semantic-analyzer framework.
///
/// This round intentionally stops at framework wiring: it builds the existing skeleton result
/// and returns one shared `FrontendAnalysisData` carrier that already owns every planned
/// side table. Binder, expression typing, member/call resolution, and diagnostics beyond the
/// skeleton stage remain future work.
public final class FrontendSemanticAnalyzer {
    private final @NotNull FrontendClassSkeletonBuilder classSkeletonBuilder;

    public FrontendSemanticAnalyzer() {
        this(new FrontendClassSkeletonBuilder());
    }

    public FrontendSemanticAnalyzer(@NotNull FrontendClassSkeletonBuilder classSkeletonBuilder) {
        this.classSkeletonBuilder = Objects.requireNonNull(classSkeletonBuilder, "classSkeletonBuilder must not be null");
    }

    /// Runs the current frontend analyzer framework against one module using a shared
    /// `DiagnosticManager`.
    ///
    /// The analyzer intentionally does not auto-import `FrontendSourceUnit.parseDiagnostics()`.
    /// Callers that construct units manually must decide whether those parse diagnostics belong
    /// to the shared pipeline manager before invoking analysis.
    public @NotNull FrontendAnalysisData analyze(
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(moduleName, "moduleName must not be null");
        Objects.requireNonNull(units, "units must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = classSkeletonBuilder.build(
                moduleName,
                units,
                classRegistry,
                diagnosticManager,
                analysisData
        );
        analysisData.publishPhaseBoundary(moduleSkeleton, diagnosticManager.snapshot());
        return analysisData;
    }
}
