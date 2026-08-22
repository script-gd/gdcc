package gd.script.gdcc.frontend.lowering.cfg.region;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Region for one `match` statement.
///
/// The region names the stable anchors inside the graph:
/// - `headerEntryId` is the subject-evaluation subgraph; it aliases `entryId()` and runs exactly
///   once before any section test
/// - `sections` holds per-section `testEntryId` / `bodyEntryId` pairs in source order
/// - `mergeId` is the fallthrough join after the full match, or a `TERMINAL_MERGE` stop when every
///   path terminates (same rule as `if`; the id must never be a `goto` target)
///
/// Bind slot ids live in the match-bind registry, not on the region, because a match may publish
/// several `PatternBindingExpression` identities.
public record FrontendMatchRegion(
        @NotNull String headerEntryId,
        @NotNull List<FrontendMatchSectionAnchors> sections,
        @NotNull String mergeId
) implements FrontendCfgRegion {
    public FrontendMatchRegion {
        headerEntryId = FrontendCfgGraph.validateNodeId(headerEntryId, "headerEntryId");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
        mergeId = FrontendCfgGraph.validateNodeId(mergeId, "mergeId");
    }

    @Override
    public @NotNull String entryId() {
        return headerEntryId;
    }
}
