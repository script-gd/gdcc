package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.Expression;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Materializes a constant already folded by semantic analysis, without evaluating its AST.
public record ConstantValueItem(
        @NotNull Expression anchor,
        @NotNull GdConstantValue value,
        @NotNull String resultValueId
) implements ValueOpItem {
    public ConstantValueItem {
        Objects.requireNonNull(anchor, "anchor must not be null");
        Objects.requireNonNull(value, "value must not be null");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
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
