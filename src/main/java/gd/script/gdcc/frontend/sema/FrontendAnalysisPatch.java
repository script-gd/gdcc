package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Incremental semantic facts published by one segmented frontend stage.
///
/// Patch contents are copied on creation so later scratch mutations cannot silently change the
/// already-drained publication payload.
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
        symbolBindings = copySideTable(symbolBindings, "symbolBindings");
        resolvedMembers = copySideTable(resolvedMembers, "resolvedMembers");
        resolvedCalls = copySideTable(resolvedCalls, "resolvedCalls");
        expressionTypes = copySideTable(expressionTypes, "expressionTypes");
        slotTypes = copySideTable(slotTypes, "slotTypes");
        localSlotTypeUpdates = List.copyOf(Objects.requireNonNull(
                localSlotTypeUpdates,
                "localSlotTypeUpdates must not be null"
        ));
    }

    private static <V> @NotNull FrontendAstSideTable<V> copySideTable(
            FrontendAstSideTable<V> source,
            @NotNull String fieldName
    ) {
        var checkedSource = Objects.requireNonNull(source, fieldName + " must not be null");
        var copy = new FrontendAstSideTable<V>();
        copy.putAll(checkedSource);
        return copy;
    }
}
