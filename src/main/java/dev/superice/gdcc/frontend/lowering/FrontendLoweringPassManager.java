package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Public frontend lowering entrypoint that executes the fixed lowering pass pipeline.
///
/// The manager currently only wires compile-ready analysis publication. Later passes will keep
/// extending the same internal pipeline instead of adding parallel public entrypoints.
public final class FrontendLoweringPassManager {
    private final @NotNull List<FrontendLoweringPass> passes;

    public FrontendLoweringPassManager() {
        this(List.of(new FrontendLoweringAnalysisPass()));
    }

    FrontendLoweringPassManager(@NotNull List<FrontendLoweringPass> passes) {
        this.passes = List.copyOf(Objects.requireNonNull(passes, "passes must not be null"));
    }

    public @Nullable LirModule lower(
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
        return context.lirModuleOrNull();
    }
}
