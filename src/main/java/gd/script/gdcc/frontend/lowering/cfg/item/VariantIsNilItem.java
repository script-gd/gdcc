package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// `null` literal match test against a Variant subject (`variant_is_nil`).
///
/// Non-Variant subjects never reach this item: the CFG builder folds them to a bool constant
/// instead. Body lowering packs the operand only when the published type is not already Variant.
public record VariantIsNilItem(
        @NotNull Node nilAnchor,
        @NotNull String operandValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public VariantIsNilItem {
        Objects.requireNonNull(nilAnchor, "nilAnchor must not be null");
        operandValueId = FrontendCfgGraph.validateValueId(operandValueId, "operandValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return nilAnchor;
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
