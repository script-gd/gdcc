package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Public frontend lowering entrypoint that executes the fixed lowering pass pipeline.
///
/// The current pipeline consumes a `FrontendModule`, runs compile-ready semantic analysis, and
/// emits a shell-only `LirModule` while also publishing lowering-local per-function scaffolding
/// and frontend CFG graphs with explicit value-op operands/results.
public final class FrontendLoweringPassManager {
    private final @NotNull List<FrontendLoweringPass> passes;

    public FrontendLoweringPassManager() {
        this(List.of(
                new FrontendLoweringAnalysisPass(),
                new FrontendLoweringClassSkeletonPass(),
                new FrontendLoweringFunctionPreparationPass(),
                new FrontendLoweringBuildCfgPass()
        ));
    }

    FrontendLoweringPassManager(@NotNull List<FrontendLoweringPass> passes) {
        this.passes = List.copyOf(Objects.requireNonNull(passes, "passes must not be null"));
    }

    public @Nullable LirModule lower(
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        return lowerToContext(module, classRegistry, diagnosticManager).lirModuleOrNull();
    }

    /// Package-local test hook that exposes the final lowering context without widening the public
    /// lowering API. The public entrypoint still returns only the published `LirModule`.
    @NotNull FrontendLoweringContext lowerToContext(
            @NotNull FrontendModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        var context = new FrontendLoweringContext(module, classRegistry, diagnosticManager);
        for (var pass : passes) {
            if (context.isStopRequested()) {
                break;
            }
            pass.run(context);
        }
        return context;
    }
}
