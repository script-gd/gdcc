package dev.superice.gdcc.frontend.lowering;

import org.jetbrains.annotations.NotNull;

/// Package-private lowering pass contract used by the fixed frontend lowering pipeline.
///
/// The pipeline is intentionally not a public extension point. Passes only coordinate through the
/// shared lowering context owned by the manager.
@FunctionalInterface
interface FrontendLoweringPass {
    void run(@NotNull FrontendLoweringContext context);
}
