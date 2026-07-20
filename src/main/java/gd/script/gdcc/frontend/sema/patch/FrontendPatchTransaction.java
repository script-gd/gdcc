package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// Ordered suite-export transaction made of single-owner patches.
///
/// "Transaction" denotes grouping and owner order only. `applyTo(...)` applies each patch immediately
/// and provides no cross-patch atomicity or rollback. If a later patch fails, earlier patches remain
/// in stable side tables, and local stabilization may already have updated scope slots and binding payloads.
///
/// The constructor rejects duplicate or out-of-order owners so callers cannot accidentally recreate
/// the legacy multi-owner patch shape at suite export time. Nested suite transactions are deferred
/// in a callable-scoped export batch and never applied at their own suite boundary.
public record FrontendPatchTransaction(@NotNull List<FrontendOwnerPatch> patches) {
    public FrontendPatchTransaction {
        patches = List.copyOf(Objects.requireNonNull(patches, "patches must not be null"));
        checkOrder(patches);
    }

    public static @NotNull FrontendPatchTransaction of(@NotNull FrontendOwnerPatch... patches) {
        return new FrontendPatchTransaction(List.of(patches));
    }

    public void applyTo(@NotNull FrontendAnalysisData analysisData) {
        var checkedData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        for (var patch : patches) {
            checkedData.applyPatch(patch);
        }
    }

    private static void checkOrder(@NotNull List<FrontendOwnerPatch> patches) {
        var seenStages = EnumSet.noneOf(FrontendSemanticStage.class);
        var previousOrder = -1;
        for (var patch : patches) {
            var stage = Objects.requireNonNull(patch, "patch must not be null").stage();
            if (!seenStages.add(stage)) {
                throw transactionFailure("duplicate owner patch " + stage);
            }
            var order = order(stage);
            if (order < previousOrder) {
                throw transactionFailure("owner patch order regressed at " + stage);
            }
            previousOrder = order;
        }
    }

    private static int order(@NotNull FrontendSemanticStage stage) {
        return switch (stage) {
            case TOP_BINDING -> 0;
            case LOCAL_TYPE_STABILIZATION -> 1;
            case CHAIN_BINDING -> 2;
            case EXPR_TYPE -> 3;
            case VAR_TYPE_POST -> 4;
        };
    }

    private static @NotNull FrontendAnalysisPatchException transactionFailure(@NotNull String message) {
        return new FrontendAnalysisPatchException("patch transaction rejected: " + message);
    }
}
