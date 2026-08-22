package gd.script.gdcc.frontend.lowering.cfg.region;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

/// AST-keyed structured overlay published alongside one `FrontendCfgGraph`.
///
/// A region is not a fourth node kind. It is metadata that points into the existing frontend CFG
/// node topology so later passes can recover structured source ownership without reverse-engineering
/// graph shape from raw edges alone:
/// - `SequenceNode` ids describe linear block/body entries and fallthrough merge continuations
/// - `BranchNode` ids describe condition-evaluation splits for structured control flow
/// - `StopNode` ids may serve as exits or merge substitutes when a construct terminates control flow
///
/// Each subtype records the stable anchors needed by one source AST node. The actual executable work
/// and control-flow edges always remain in the graph itself.
public sealed interface FrontendCfgRegion
        permits FrontendCfgRegion.BlockRegion,
        FrontendIfRegion,
        FrontendElifRegion,
        FrontendWhileRegion,
        FrontendForRegion,
        FrontendMatchRegion {
    /// First graph node reached when control enters the source region.
    @NotNull String entryId();

    /// Region for one lexical `Block`.
    ///
    /// Block regions usually begin at a `SequenceNode`, because straight-line statements are
    /// materialized there. The region still stores only the id, not the node payload, so later
    /// passes can keep one lookup surface for all structured constructs.
    record BlockRegion(@NotNull String entryId) implements FrontendCfgRegion {
        public BlockRegion {
            entryId = FrontendCfgGraph.validateNodeId(entryId, "entryId");
        }
    }
}
