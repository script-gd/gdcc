package gd.script.gdcc.frontend.lowering.pass;

import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPass;
import gd.script.gdcc.lir.LirModule;
import org.jetbrains.annotations.NotNull;

/// Lowering pass that emits the backend-facing module shell directly from published frontend
/// class skeletons.
///
/// The pass reuses the already-built `LirClassDef` objects from `FrontendModuleSkeleton` instead
/// of rebuilding or cloning them.
public final class FrontendLoweringClassSkeletonPass implements FrontendLoweringPass {
    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        var moduleSkeleton = analysisData.moduleSkeleton();
        // Sema marks coroutine callables on the same `LirFunctionDef` shell objects the skeleton
        // already published (`FrontendAnalysisData.coroutineFunctions`, identity-keyed), so the
        // attribute propagates by object identity without any name-based lookup. The set is
        // monotonic: functions absent from it keep the default `false`.
        for (var classDef : moduleSkeleton.allClassDefs()) {
            for (var functionDef : classDef.getFunctions()) {
                if (analysisData.coroutineFunctions().contains(functionDef)) {
                    functionDef.setCoroutine(true);
                }
            }
        }
        context.publishLirModule(new LirModule(
                moduleSkeleton.moduleName(),
                moduleSkeleton.allClassDefs()
        ));
    }
}
