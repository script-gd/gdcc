package dev.superice.gdcc.frontend.lowering.cfg.region;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

/// Region for one `if` statement.
///
/// The region names four stable anchors inside the graph:
/// - `conditionEntryId` is the stable entry of the full condition-evaluation subgraph; consumers
///   must treat it as an opaque region entry instead of assuming a fixed `SequenceNode ->
///   BranchNode` two-node shape
/// - `thenEntryId` is the first node of the `then` body, usually a `SequenceNode`
/// - `elseOrNextClauseEntryId` is the first node of the `else` body or chained `elif` clause
/// - `mergeId` is the fallthrough join after the full `if` chain, or a `StopNode`-like exit when
///   all paths terminate before normal fallthrough
///
/// `entryId()` aliases `conditionEntryId` because condition evaluation is the true entry of the
/// whole `if` region.
public record FrontendIfRegion(
        @NotNull String conditionEntryId,
        @NotNull String thenEntryId,
        @NotNull String elseOrNextClauseEntryId,
        @NotNull String mergeId
) implements FrontendCfgRegion {
    public FrontendIfRegion {
        conditionEntryId = FrontendCfgGraph.validateNodeId(conditionEntryId, "conditionEntryId");
        thenEntryId = FrontendCfgGraph.validateNodeId(thenEntryId, "thenEntryId");
        elseOrNextClauseEntryId = FrontendCfgGraph.validateNodeId(
                elseOrNextClauseEntryId,
                "elseOrNextClauseEntryId"
        );
        mergeId = FrontendCfgGraph.validateNodeId(mergeId, "mergeId");
    }

    @Override
    public @NotNull String entryId() {
        return conditionEntryId;
    }
}
