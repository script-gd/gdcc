package dev.superice.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Writes one already-materialized branch-local value into a shared merged result slot.
///
/// Future branchy value expressions such as short-circuit `and` / `or` and
/// `ConditionalExpression` must let both arms publish the same outward-facing result id before
/// control flow rejoins. Making that write explicit keeps the parent contract one-way: children
/// publish value ids, and parent items consume only those ids instead of re-reading child AST.
public record MergeValueItem(
        @NotNull Node mergeAnchor,
        @NotNull String sourceValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public MergeValueItem {
        Objects.requireNonNull(mergeAnchor, "mergeAnchor must not be null");
        sourceValueId = FrontendCfgGraph.validateValueId(sourceValueId, "sourceValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return mergeAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(sourceValueId);
    }
}
