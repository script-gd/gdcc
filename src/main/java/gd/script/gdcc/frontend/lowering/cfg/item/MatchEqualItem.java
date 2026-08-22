package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Value equality used by match LITERAL / EXPRESSION tests (`Variant::OP_EQUAL`).
///
/// Both operands are already-materialized CFG value ids. Body lowering emits `binary_op "EQUAL"`
/// after any boundary packing required by the published operand types. The result is a bool.
public record MatchEqualItem(
        @NotNull Node equalAnchor,
        @NotNull String leftValueId,
        @NotNull String rightValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public MatchEqualItem {
        Objects.requireNonNull(equalAnchor, "equalAnchor must not be null");
        leftValueId = FrontendCfgGraph.validateValueId(leftValueId, "leftValueId");
        rightValueId = FrontendCfgGraph.validateValueId(rightValueId, "rightValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return equalAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(leftValueId, rightValueId);
    }
}
