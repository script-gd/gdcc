package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared mutable lowering state passed between fixed frontend lowering passes.
final class FrontendLoweringContext {
    private final @NotNull FrontendModule module;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull DiagnosticManager diagnosticManager;
    private @Nullable FrontendAnalysisData analysisData;
    private @Nullable LirModule lirModule;
    private boolean stopRequested;

    FrontendLoweringContext(
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        this.module = Objects.requireNonNull(module, "module must not be null");
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
    }

    @NotNull FrontendModule module() {
        return module;
    }

    @NotNull ClassRegistry classRegistry() {
        return classRegistry;
    }

    @NotNull DiagnosticManager diagnosticManager() {
        return diagnosticManager;
    }

    void publishAnalysisData(@NotNull FrontendAnalysisData analysisData) {
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
    }

    @Nullable FrontendAnalysisData analysisDataOrNull() {
        return analysisData;
    }

    @NotNull FrontendAnalysisData requireAnalysisData() {
        if (analysisData == null) {
            throw new IllegalStateException("analysisData has not been published yet");
        }
        return analysisData;
    }

    void publishLirModule(@NotNull LirModule lirModule) {
        this.lirModule = Objects.requireNonNull(lirModule, "lirModule must not be null");
    }

    @Nullable LirModule lirModuleOrNull() {
        return lirModule;
    }

    void requestStop() {
        stopRequested = true;
    }

    boolean isStopRequested() {
        return stopRequested;
    }
}
