package dev.superice.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Explicit boolean constant materialization for branchy value expressions.
///
/// Short-circuit `and` / `or` do not always have a source literal node on the taken path, but they
/// still need to publish `true` / `false` into a shared merged result slot before control rejoins.
/// This item keeps that constant write first-class without inventing synthetic source AST literals.
public record BoolConstantItem(
        @NotNull Node constantAnchor,
        boolean value,
        @NotNull String resultValueId
) implements ValueOpItem {
    public BoolConstantItem {
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
