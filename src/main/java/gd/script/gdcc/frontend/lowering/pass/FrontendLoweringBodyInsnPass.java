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
                // Lambda bodies share the executable-body session: the synthetic shell is marked
                // static, but a self-capturing lambda still publishes a captured local `self`
                // slot. The shell carries its own published CFG graph.
                // Parameter-default shells are isomorphic to property-init shells for body
                // lowering: an expression-rooted graph closed by a RETURN stop, and the instance
                // flavor's leading `self` parameter is declared by the shared self-slot path.
                case EXECUTABLE_BODY, PROPERTY_INIT, LAMBDA_BODY, PARAMETER_DEFAULT_INIT ->
                        new FrontendBodyLoweringSession(functionContext, context.classRegistry()).run();
            }
        }
    }
}
