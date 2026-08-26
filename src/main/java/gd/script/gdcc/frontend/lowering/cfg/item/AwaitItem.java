package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Await placeholder for one already-materialized operand value
/// (`frontend_await_implementation.md` §9).
///
/// The operand is always built first as an ordinary value (signal read, call result, or Variant
/// value); this item only makes the suspension point explicit in data-flow form. Body lowering
/// dispatches strictly on the operand's materialized slot type plus published call facts — it never
/// re-derives the semantic route from raw AST.
///
/// The single exception with no operand value is a redundant await on a resolved-void
/// non-coroutine call: the call runs for side effects through the no-result statement path, and the
/// await result is nil (Godot `REDUNDANT_AWAIT` contract), so `operandValueIdOrNull` stays null.
public record AwaitItem(
        @NotNull AwaitExpression expression,
        @Nullable String operandValueIdOrNull,
        @NotNull String resultValueId
) implements ValueOpItem {
    public AwaitItem {
        Objects.requireNonNull(expression, "expression must not be null");
        operandValueIdOrNull = FrontendCfgItemSupport.validateOptionalValueId(operandValueIdOrNull, "operandValueIdOrNull");
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
        return operandValueIdOrNull == null ? List.of() : List.of(operandValueIdOrNull);
    }
}
