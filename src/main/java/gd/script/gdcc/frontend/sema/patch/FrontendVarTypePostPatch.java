package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

/// Var-type-post owner delta. It may only publish final source-facing slot types.
public record FrontendVarTypePostPatch(
        @NotNull FrontendAstSideTable<GdType> slotTypes
) implements FrontendOwnerPatch {
    public FrontendVarTypePostPatch {
        slotTypes = FrontendPatchTables.copySideTable(slotTypes, "slotTypes");
        FrontendPublishedFactTypeGuard.checkSlotTypes(slotTypes);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.VAR_TYPE_POST;
    }
}
