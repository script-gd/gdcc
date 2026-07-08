package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Legacy incremental semantic facts published by one segmented frontend stage.
///
/// Phase C production export uses `FrontendOwnerPatch` and `FrontendPatchTransaction`. This record
/// remains as the compatibility carrier for pre-existing window tests and legacy segmented shims.
public record FrontendAnalysisPatch(
        @NotNull FrontendSemanticStage stage,
        @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
        @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
        @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
        @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
        @NotNull FrontendAstSideTable<GdType> slotTypes,
        @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
) {
    public FrontendAnalysisPatch {
        Objects.requireNonNull(stage, "stage must not be null");
        symbolBindings = FrontendPatchTables.copySideTable(symbolBindings, "symbolBindings");
        resolvedMembers = FrontendPatchTables.copySideTable(resolvedMembers, "resolvedMembers");
        resolvedCalls = FrontendPatchTables.copySideTable(resolvedCalls, "resolvedCalls");
        expressionTypes = FrontendPatchTables.copySideTable(expressionTypes, "expressionTypes");
        slotTypes = FrontendPatchTables.copySideTable(slotTypes, "slotTypes");
        localSlotTypeUpdates = List.copyOf(Objects.requireNonNull(
                localSlotTypeUpdates,
                "localSlotTypeUpdates must not be null"
        ));
        FrontendPublishedFactTypeGuard.checkSymbolBindings(symbolBindings);
        FrontendPublishedFactTypeGuard.checkResolvedMembers(resolvedMembers);
        FrontendPublishedFactTypeGuard.checkResolvedCalls(resolvedCalls);
        FrontendPublishedFactTypeGuard.checkExpressionTypes(expressionTypes);
        FrontendPublishedFactTypeGuard.checkSlotTypes(slotTypes);
        FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(localSlotTypeUpdates);
    }
}
