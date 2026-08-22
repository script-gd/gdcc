package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Explicit integer constant used by match type-id comparisons.
///
/// `get_variant_type` results are compared against Godot `Variant.Type` ordinals. Those ordinals
/// are not source literals, so the CFG publishes them as first-class values instead of letting
/// body lowering invent integers while walking AST.
public record IntConstantItem(
        @NotNull Node constantAnchor,
        long value,
        @NotNull String resultValueId
) implements ValueOpItem {
    public IntConstantItem {
        Objects.requireNonNull(constantAnchor, "constantAnchor must not be null");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return constantAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of();
    }
}
