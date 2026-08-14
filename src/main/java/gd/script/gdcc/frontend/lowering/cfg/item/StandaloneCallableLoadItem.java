package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Objects;

/// Qualified static method-reference placeholder for a published `RESOLVED` STATIC_METHOD.
///
/// This item has no instance receiver. Bare static/utility identifiers stay on the opaque path.
public record StandaloneCallableLoadItem(
        @NotNull Node memberAnchor,
        @NotNull StandaloneCallableKind kind,
        @NotNull String ownerName,
        @NotNull String callableName,
        @NotNull String resultValueId
) implements ValueOpItem {
    public StandaloneCallableLoadItem {
        Objects.requireNonNull(memberAnchor, "memberAnchor must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == StandaloneCallableKind.UTILITY) {
            throw new IllegalArgumentException("qualified standalone loads cannot use utility kind");
        }
        ownerName = StringUtil.requireNonBlank(ownerName, "ownerName");
        callableName = StringUtil.requireNonBlank(callableName, "callableName");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return memberAnchor;
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
