package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Dedicated CFG value item for one array or dictionary literal root.
///
/// Child operands are already evaluated in source order before this item is appended:
/// - Array: element0..elementN
/// - Dictionary: key0/value0/key1/value1/...
///
/// Body lowering must consume {@link gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan}
/// for operand materialization; this item never re-walks the AST children. A dedicated body
/// processor materializes the operands and emits the `construct_container_literal` LIR instruction.
public record ContainerLiteralItem(
        @NotNull Expression expression,
        @NotNull List<String> operandValueIds,
        @NotNull String resultValueId
) implements ValueOpItem {
    public ContainerLiteralItem {
        Objects.requireNonNull(expression, "expression must not be null");
        if (!(expression instanceof ArrayExpression) && !(expression instanceof DictionaryExpression)) {
            throw new IllegalArgumentException(
                    "ContainerLiteralItem expression must be ArrayExpression or DictionaryExpression, got "
                            + expression.getClass().getSimpleName()
            );
        }
        operandValueIds = FrontendCfgItemSupport.copyValueIds(operandValueIds, "operandValueIds");
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
        return operandValueIds;
    }
}
