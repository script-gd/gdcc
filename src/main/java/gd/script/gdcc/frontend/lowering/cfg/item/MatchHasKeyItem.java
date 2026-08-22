package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Key-existence gate of one DICTIONARY pattern entry.
///
/// The dictionary operand must already carry a static `Dictionary` slot type (published by
/// `MatchContainerMaterializeItem`); the key is the entry's constant expression value. Body
/// lowering packs the key to Variant and emits the builtin `has(key)` call, mirroring Godot
/// compiler's `write_call(value, "has", key)` in `_parse_match_pattern`. The bool result feeds
/// the pattern's branch chain; the value fetch happens only on the has-true edge.
public record MatchHasKeyItem(
        @NotNull Node keyAnchor,
        @NotNull String dictionaryValueId,
        @NotNull String keyValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public MatchHasKeyItem {
        Objects.requireNonNull(keyAnchor, "keyAnchor must not be null");
        dictionaryValueId = FrontendCfgGraph.validateValueId(dictionaryValueId, "dictionaryValueId");
        keyValueId = FrontendCfgGraph.validateValueId(keyValueId, "keyValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return keyAnchor;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(dictionaryValueId, keyValueId);
    }
}
