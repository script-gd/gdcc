package gd.script.gdcc.frontend.lowering.cfg.region;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

/// Per-section anchors inside one `FrontendMatchRegion`.
///
/// `testEntryId` is the first node of that section's pattern/guard test subgraph. `bodyEntryId` is
/// the first node of the section body, which is a sequence that commits any top-level binds before
/// the already-built body statements. A catch-all section (`_` / `var x` without a guard) may use
/// the same id for both when there is no test subgraph.
public record FrontendMatchSectionAnchors(
        @NotNull String testEntryId,
        @NotNull String bodyEntryId
) {
    public FrontendMatchSectionAnchors {
        testEntryId = FrontendCfgGraph.validateNodeId(testEntryId, "testEntryId");
        bodyEntryId = FrontendCfgGraph.validateNodeId(bodyEntryId, "bodyEntryId");
    }
}
