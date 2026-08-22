package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Length gate of one ARRAY / DICTIONARY destructuring pattern.
///
/// The container operand must already be materialized into a static container slot by
/// `MatchContainerMaterializeItem`. Body lowering emits the builtin `size()` call, a literal count
/// and one comparison: `==` when the pattern is closed, `>=` when it ends with `..` (the rest
/// marker itself is not counted and never captures elements, aligned with Godot §1.2-5/6 of the
/// match plan). The bool result feeds the pattern's branch chain.
public record MatchLengthCheckItem(
        @NotNull Node containerAnchor,
        @NotNull String containerValueId,
        long expectedCount,
        boolean openEnded,
        @NotNull String resultValueId
) implements ValueOpItem {
    public MatchLengthCheckItem {
        Objects.requireNonNull(containerAnchor, "containerAnchor must not be null");
        containerValueId = FrontendCfgGraph.validateValueId(containerValueId, "containerValueId");
        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount must not be negative, but got " + expectedCount);
        }
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return containerAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(containerValueId);
    }
}
