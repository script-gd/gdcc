package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

/// Chain-binding owner delta. It publishes member facts and chain-owned call facts only.
public record FrontendChainBindingPatch(
        @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
        @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls
) implements FrontendOwnerPatch {
    public FrontendChainBindingPatch {
        resolvedMembers = FrontendPatchTables.copySideTable(resolvedMembers, "resolvedMembers");
        resolvedCalls = FrontendPatchTables.copySideTable(resolvedCalls, "resolvedCalls");
        FrontendPublishedFactTypeGuard.checkResolvedMembers(resolvedMembers);
        FrontendPublishedFactTypeGuard.checkResolvedCalls(resolvedCalls);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.CHAIN_BINDING;
    }
}
