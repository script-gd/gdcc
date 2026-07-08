package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

/// Top-binding owner delta. It may only publish `symbolBindings()` facts.
public record FrontendTopBindingPatch(
        @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings
) implements FrontendOwnerPatch {
    public FrontendTopBindingPatch {
        symbolBindings = FrontendPatchTables.copySideTable(symbolBindings, "symbolBindings");
        FrontendPublishedFactTypeGuard.checkSymbolBindings(symbolBindings);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.TOP_BINDING;
    }
}
