package dev.superice.gdcc.frontend.lowering.cfg.region;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

/// Region for one `while` loop.
///
/// The region separates the loop's structural anchors:
/// - `conditionEntryId` is the stable entry of the full loop condition subgraph; `continue`
///   targets must treat it as an opaque entry instead of assuming one fixed `SequenceNode ->
///   BranchNode` pair
/// - `bodyEntryId` is the first node of the loop body, usually a `SequenceNode`
/// - `exitId` is the first node after the loop, or a `StopNode`-like exit when control flow does
///   not fall through normally
///
/// `entryId()` aliases `conditionEntryId` so later `continue`-style lowering can target the loop's
/// condition entry directly without rediscovering it from graph edges.
public record FrontendWhileRegion(
        @NotNull String conditionEntryId,
        @NotNull String bodyEntryId,
        @NotNull String exitId
) implements FrontendCfgRegion {
    public FrontendWhileRegion {
        conditionEntryId = FrontendCfgGraph.validateNodeId(conditionEntryId, "conditionEntryId");
        bodyEntryId = FrontendCfgGraph.validateNodeId(bodyEntryId, "bodyEntryId");
        exitId = FrontendCfgGraph.validateNodeId(exitId, "exitId");
    }

    @Override
    public @NotNull String entryId() {
        return conditionEntryId;
    }
}
