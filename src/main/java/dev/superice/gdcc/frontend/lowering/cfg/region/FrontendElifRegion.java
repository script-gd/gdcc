package dev.superice.gdcc.frontend.lowering.cfg.region;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

/// Region for one `elif` clause inside an `if` chain.
///
/// `conditionEntryId` is the stable entry of the clause's full condition subgraph, `bodyEntryId`
/// is the first node of the clause body, and `nextClauseOrMergeId` points either to the next
/// `elif` / `else` entry or to the shared merge / exit of the surrounding `if` chain.
public record FrontendElifRegion(
        @NotNull String conditionEntryId,
        @NotNull String bodyEntryId,
        @NotNull String nextClauseOrMergeId
) implements FrontendCfgRegion {
    public FrontendElifRegion {
        conditionEntryId = FrontendCfgGraph.validateNodeId(conditionEntryId, "conditionEntryId");
        bodyEntryId = FrontendCfgGraph.validateNodeId(bodyEntryId, "bodyEntryId");
        nextClauseOrMergeId = FrontendCfgGraph.validateNodeId(nextClauseOrMergeId, "nextClauseOrMergeId");
    }

    @Override
    public @NotNull String entryId() {
        return conditionEntryId;
    }
}
