package gd.script.gdcc.frontend.lowering.cfg.region;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

/// Region for one `for-in` loop.
///
/// The region separates the loop's five structural anchors plus the two dedicated iterator slots:
/// - `initEntryId` is the entry of the init subgraph that materializes the source operands and runs
///   the route's init operation into the hidden iterator state slot; it aliases `entryId()`
/// - `conditionEntryId` is the stable entry of the should-continue condition subgraph; `continue`
///   must NOT target it directly (continue targets the update entry instead)
/// - `bodyEntryId` is the first node of the loop body, a sequence that runs the get operation to
///   commit the source-facing iterator local before the body statements
/// - `updateEntryId` is the entry that runs the next operation and jumps back to `conditionEntryId`;
///   it is the `continue` target
/// - `exitId` is the first node after the loop; it is the `break` target
///
/// `sourceIteratorSlotId` and `iteratorStateSlotId` are slot references resolved through the
/// source-slot registry and the hidden-state registry respectively. They are not frontend CFG node
/// ids and must never be interpreted through the node-id validator, value-id producer map or ordinary
/// operand surface. The two slots come from different registries and never share id, type or lifecycle.
public record FrontendForRegion(
        @NotNull String initEntryId,
        @NotNull String conditionEntryId,
        @NotNull String bodyEntryId,
        @NotNull String updateEntryId,
        @NotNull String exitId,
        @NotNull String sourceIteratorSlotId,
        @NotNull String iteratorStateSlotId
) implements FrontendCfgRegion {
    public FrontendForRegion {
        initEntryId = FrontendCfgGraph.validateNodeId(initEntryId, "initEntryId");
        conditionEntryId = FrontendCfgGraph.validateNodeId(conditionEntryId, "conditionEntryId");
        bodyEntryId = FrontendCfgGraph.validateNodeId(bodyEntryId, "bodyEntryId");
        updateEntryId = FrontendCfgGraph.validateNodeId(updateEntryId, "updateEntryId");
        exitId = FrontendCfgGraph.validateNodeId(exitId, "exitId");
        sourceIteratorSlotId = FrontendCfgGraph.validateNodeId(sourceIteratorSlotId, "sourceIteratorSlotId");
        iteratorStateSlotId = FrontendCfgGraph.validateNodeId(iteratorStateSlotId, "iteratorStateSlotId");
        if (sourceIteratorSlotId.equals(iteratorStateSlotId)) {
            throw new IllegalArgumentException(
                    "for-in source iterator slot and hidden state slot must use distinct ids, but both were '"
                            + sourceIteratorSlotId + "'"
            );
        }
    }

    @Override
    public @NotNull String entryId() {
        return initEntryId;
    }
}
