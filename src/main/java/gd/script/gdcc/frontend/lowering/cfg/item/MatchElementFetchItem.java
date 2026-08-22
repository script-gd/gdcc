package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Fetches one sub-element value out of a materialized container during destructuring.
///
/// The container operand must carry a static container slot type: `Array` lowers to
/// `variant_get_indexed` with the already-materialized int index, `Dictionary` lowers to
/// `variant_get_keyed` with the key packed to Variant. The fetch is only reachable behind the
/// length / key-existence gates, so out-of-range and missing-key reads cannot occur. The result
/// is always a Variant temp consumed by the recursive sub-pattern test (or a nested bind).
public record MatchElementFetchItem(
        @NotNull Node elementAnchor,
        @NotNull String containerValueId,
        @NotNull String keyValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public MatchElementFetchItem {
        Objects.requireNonNull(elementAnchor, "elementAnchor must not be null");
        containerValueId = FrontendCfgGraph.validateValueId(containerValueId, "containerValueId");
        keyValueId = FrontendCfgGraph.validateValueId(keyValueId, "keyValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return elementAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(containerValueId, keyValueId);
    }
}
