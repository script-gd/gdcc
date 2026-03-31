package dev.superice.gdcc.frontend.lowering.pass;

import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPass;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraphBuilder;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import org.jetbrains.annotations.NotNull;

/// Frontend CFG graph publication pass.
///
/// The current implementation materializes the linear executable-body subset into the new frontend
/// CFG graph with recursive value-op expansion for lowering-ready expressions. Structured bodies and
/// short-circuit `and` / `or` still wait for later migration steps, so this pass intentionally skips
/// only the former and relies on the compile gate to keep the latter out of compile-ready lowering.
/// Legacy `FrontendLoweringCfgPass` remains in the default pipeline as a temporary metadata-only
/// fallback for that broader surface.
public final class FrontendLoweringBuildCfgPass implements FrontendLoweringPass {
    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        var lirModule = context.requireLirModule();
        var functionLoweringContexts = context.requireFunctionLoweringContexts();

        for (var functionContext : functionLoweringContexts) {
            if (functionContext.analysisData() != analysisData) {
                throw new IllegalStateException("Function lowering context must reuse the published analysis snapshot");
            }
            validateTargetFunctionMembership(functionContext, lirModule);
            switch (functionContext.kind()) {
                case EXECUTABLE_BODY -> publishStraightLineExecutableGraph(functionContext);
                case PROPERTY_INIT -> validateShellOnlyTarget(functionContext);
                case PARAMETER_DEFAULT_INIT -> throw new IllegalStateException(
                        "Frontend CFG build pass does not support parameter default initializer contexts yet"
                );
            }
        }
    }

    private void publishStraightLineExecutableGraph(@NotNull FunctionLoweringContext functionContext) {
        if (!(functionContext.sourceOwner() instanceof FunctionDeclaration)
                && !(functionContext.sourceOwner() instanceof ConstructorDeclaration)) {
            throw new IllegalStateException(describeContext(functionContext) + " must keep a callable declaration as sourceOwner");
        }
        if (!(functionContext.loweringRoot() instanceof Block rootBlock)) {
            throw new IllegalStateException(describeContext(functionContext) + " must expose a Block loweringRoot");
        }
        validateShellOnlyTarget(functionContext);
        if (!FrontendCfgGraphBuilder.supportsStraightLineExecutableBody(rootBlock)) {
            return;
        }

        var build = new FrontendCfgGraphBuilder().buildStraightLineExecutableBody(rootBlock, functionContext.analysisData());
        functionContext.publishFrontendCfgGraph(build.graph());
        functionContext.publishFrontendCfgRegion(rootBlock, build.rootRegion());
    }

    private void validateTargetFunctionMembership(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull LirModule lirModule
    ) {
        if (lirModule.getClassDefs().stream().noneMatch(classDef -> classDef == functionContext.owningClass())) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " references an owningClass outside the published LIR module"
            );
        }
        if (functionContext.owningClass().getFunctions().stream().noneMatch(function -> function == functionContext.targetFunction())) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " references a targetFunction outside the owning LIR class"
            );
        }
    }

    private void validateShellOnlyTarget(@NotNull FunctionLoweringContext functionContext) {
        if (functionContext.targetFunction().getBasicBlockCount() != 0
                || !functionContext.targetFunction().getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " must remain shell-only before frontend CFG materialization"
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
