package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Published value id that aliases one trusted direct slot instead of materializing `cfg_tmp_*`.
///
/// This surface is intentionally narrow:
/// - the anchor must be a direct-slot syntax root (`IdentifierExpression` or explicit `SelfExpression`)
/// - body lowering will resolve the final slot from already-published binding facts
/// - explicit `SelfExpression` is stable because user code cannot rebind the `self` slot
/// - identifier-backed roots are published only after builder proves that later argument evaluation
///   stays inside the current no-rebinding subset; otherwise builder keeps the ordinary
///   `OpaqueExprValueItem(identifier)` snapshot instead of publishing a live-slot alias
/// - capture-backed identifiers are excluded because current lambda/capture lowering does not publish
///   a stable direct-slot storage contract for them
/// - ordinary identifier/self reads keep using `OpaqueExprValueItem`; only the direct-slot mutating
///   receiver path is allowed to publish this alias item today
public record DirectSlotAliasValueItem(
        @NotNull Expression expression,
        @NotNull String resultValueId
) implements ValueOpItem {
    public DirectSlotAliasValueItem {
        Objects.requireNonNull(expression, "expression must not be null");
        if (!(expression instanceof IdentifierExpression) && !(expression instanceof SelfExpression)) {
            throw new IllegalArgumentException(
                    "DirectSlotAliasValueItem requires IdentifierExpression or SelfExpression, but got "
                            + expression.getClass().getSimpleName()
            );
        }
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
        return List.of();
    }

    @Override
    public boolean hasStandaloneMaterializationSlot() {
        return false;
    }
}
