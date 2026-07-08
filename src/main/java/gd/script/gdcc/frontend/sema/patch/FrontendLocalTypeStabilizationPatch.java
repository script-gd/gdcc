package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Local type stabilization owner delta. It carries only source-facing local slot rewrites.
public record FrontendLocalTypeStabilizationPatch(
        @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
) implements FrontendOwnerPatch {
    public FrontendLocalTypeStabilizationPatch {
        localSlotTypeUpdates = List.copyOf(Objects.requireNonNull(
                localSlotTypeUpdates,
                "localSlotTypeUpdates must not be null"
        ));
        FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(localSlotTypeUpdates);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.LOCAL_TYPE_STABILIZATION;
    }
}
