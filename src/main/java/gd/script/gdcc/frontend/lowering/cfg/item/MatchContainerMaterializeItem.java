package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.sema.FrontendMatchPatternRoute;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Materializes a match subject (or a nested fetched element) into a static `Array` / `Dictionary`
/// slot after the destructuring typeof gate has passed.
///
/// A Variant operand is unpacked once into the untyped container slot, while an already-static
/// container operand keeps its published container type. Body lowering performs the boundary
/// materialization and assigns the result into this item's temp slot; downstream length / key /
/// fetch items then consume the static container value directly.
public record MatchContainerMaterializeItem(
        @NotNull Node containerAnchor,
        @NotNull String sourceValueId,
        @NotNull FrontendMatchPatternRoute containerRoute,
        @NotNull String resultValueId
) implements ValueOpItem {
    public MatchContainerMaterializeItem {
        Objects.requireNonNull(containerAnchor, "containerAnchor must not be null");
        sourceValueId = FrontendCfgGraph.validateValueId(sourceValueId, "sourceValueId");
        Objects.requireNonNull(containerRoute, "containerRoute must not be null");
        if (containerRoute != FrontendMatchPatternRoute.ARRAY
                && containerRoute != FrontendMatchPatternRoute.DICTIONARY) {
            throw new IllegalArgumentException(
                    "containerRoute must be ARRAY or DICTIONARY, but got " + containerRoute
            );
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
        return List.of(sourceValueId);
    }
}
