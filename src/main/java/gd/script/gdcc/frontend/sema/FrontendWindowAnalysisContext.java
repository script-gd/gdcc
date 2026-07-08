package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.sema.patch.FrontendAnalysisPatch;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Stable analysis data plus one window-local scratch publication surface.
public record FrontendWindowAnalysisContext(
        @NotNull FrontendAnalysisData stableData,
        @NotNull FrontendWindowPublicationSurface publications
) {
    public FrontendWindowAnalysisContext {
        Objects.requireNonNull(stableData, "stableData must not be null");
        Objects.requireNonNull(publications, "publications must not be null");
    }

    public FrontendWindowAnalysisContext(@NotNull FrontendAnalysisData stableData) {
        this(stableData, new FrontendWindowPublicationSurface(stableData));
    }

    public @NotNull FrontendAnalysisPatch toPatch(@NotNull FrontendSemanticStage stage) {
        return publications.toPatch(stage);
    }

    public @NotNull FrontendAnalysisPatch drainPatch(@NotNull FrontendSemanticStage stage) {
        return publications.drainPatch(stage);
    }

    public void discard() {
        publications.discard();
    }
}
