package dev.superice.gdcc.frontend.lowering.pass;

import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPass;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.pass.body.FrontendBodyLoweringSession;
import org.jetbrains.annotations.NotNull;

/// Frontend CFG -> LIR body materialization pass.
///
/// The pass consumes only the frontend CFG graph plus already-published semantic facts. It must not
/// re-run chain reduction, overload selection, or child-evaluation planning.
public final class FrontendLoweringBodyInsnPass implements FrontendLoweringPass {
    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        for (var functionContext : context.requireFunctionLoweringContexts()) {
            if (functionContext.analysisData() != analysisData) {
                throw new IllegalStateException("Function lowering context must reuse the published analysis snapshot");
            }
            switch (functionContext.kind()) {
                case EXECUTABLE_BODY -> new FrontendBodyLoweringSession(functionContext).run();
                case PROPERTY_INIT -> requireShellOnlyTarget(functionContext);
                case PARAMETER_DEFAULT_INIT -> throw new IllegalStateException(
                        "Frontend body lowering pass does not support parameter default initializer contexts yet"
                );
            }
        }
    }

    private static void requireShellOnlyTarget(@NotNull FunctionLoweringContext functionContext) {
        if (functionContext.targetFunction().getBasicBlockCount() != 0
                || !functionContext.targetFunction().getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " must remain shell-only before body lowering"
            );
        }
    }

    private static @NotNull String describeContext(@NotNull FunctionLoweringContext functionContext) {
        return "Function lowering context "
                + functionContext.kind()
                + " "
                + functionContext.owningClass().getName()
                + "."
                + functionContext.targetFunction().getName();
    }
}
