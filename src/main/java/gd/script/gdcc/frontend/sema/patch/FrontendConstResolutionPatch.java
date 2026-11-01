package gd.script.gdcc.frontend.sema.patch;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.constant.Evaluation;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Const-resolution owner delta. It may only publish local constant evaluation facts and the
/// constant type updates that backfill their exact slot types — never ordinary local slot types.
public record FrontendConstResolutionPatch(
        @NotNull FrontendAstSideTable<Evaluation> constantFacts,
        @NotNull List<FrontendConstTypeUpdate> constantTypeUpdates
) implements FrontendOwnerPatch {
    public FrontendConstResolutionPatch {
        constantFacts = FrontendPatchTables.copySideTable(constantFacts, "constantFacts");
        constantTypeUpdates = List.copyOf(
                Objects.requireNonNull(constantTypeUpdates, "constantTypeUpdates must not be null")
        );
        FrontendPublishedFactTypeGuard.checkConstantFacts(constantFacts);
        FrontendPublishedFactTypeGuard.checkConstTypeUpdates(constantTypeUpdates);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.CONST_RESOLUTION;
    }
}
