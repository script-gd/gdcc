package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Reads the Godot `Variant.Type` ordinal of one already-materialized operand.
///
/// Match lowering reuses this for the subject (once per statement, when the subject is Variant /
/// statically unknown) and for runtime-submode pattern values. Body lowering packs a non-Variant
/// operand before emitting `get_variant_type`.
public record GetVariantTypeItem(
        @NotNull Node typeAnchor,
        @NotNull String operandValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public GetVariantTypeItem {
        Objects.requireNonNull(typeAnchor, "typeAnchor must not be null");
        operandValueId = FrontendCfgGraph.validateValueId(operandValueId, "operandValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return typeAnchor;
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
