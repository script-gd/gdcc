package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Explicit CFG item for GDScript `is` / `is not` (`TypeTestExpression`).
///
/// Consumes one operand value and publishes one bool result. Body lowering emits a unified
/// `is_instance_of` instruction or a folded bool constant; it does not re-resolve the RHS type
/// (that fact lives in `typeTestTargets`).
public record TypeTestItem(
        @NotNull TypeTestExpression expression,
        @NotNull String operandValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public TypeTestItem {
        Objects.requireNonNull(expression, "expression must not be null");
        operandValueId = FrontendCfgGraph.validateValueId(operandValueId, "operandValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return expression;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(operandValueId);
    }
}
