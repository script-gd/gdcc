package gd.script.gdcc.frontend.lowering.pass;

import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPass;
import gd.script.gdcc.frontend.lowering.pass.body.FrontendBodyLoweringSession;
import org.jetbrains.annotations.NotNull;

/// Frontend CFG -> LIR body materialization pass.
///
/// The pass consumes only the frontend CFG graph plus already-published semantic facts for every
/// function-shaped lowering unit that has already passed compile gate. It must not re-run chain
/// reduction, overload selection, or child-evaluation planning.
public final class FrontendLoweringBodyInsnPass implements FrontendLoweringPass {
    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        for (var functionContext : context.requireFunctionLoweringContexts()) {
            if (functionContext.analysisData() != analysisData) {
                throw new IllegalStateException("Function lowering context must reuse the published analysis snapshot");
            }
            switch (functionContext.kind()) {
                // Lambda bodies share the executable-body session: the synthetic shell is static
                // (no self slot) and carries its own published CFG graph.
                case EXECUTABLE_BODY, PROPERTY_INIT, LAMBDA_BODY ->
                        new FrontendBodyLoweringSession(functionContext, context.classRegistry()).run();
                case PARAMETER_DEFAULT_INIT -> throw new IllegalStateException(
                        "Frontend body lowering pass does not support parameter default initializer contexts yet"
                );
            }
        }
    }
}
