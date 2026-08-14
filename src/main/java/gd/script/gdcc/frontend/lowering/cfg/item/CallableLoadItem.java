package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Receiver-qualified instance method-reference placeholder for a published `RESOLVED` METHOD.
///
/// This item is intentionally distinct from `MemberLoadItem`: a method-reference constructs a
/// fresh `godot_Callable` and must not publish a property writable route. Bare method identifiers
/// stay on the opaque-expression path and never become a `CallableLoadItem`.
///
/// Object/self and non-Dictionary builtin instance methods land here. Static/utility references
/// use `StandaloneCallableLoadItem` or the opaque path.
public record CallableLoadItem(
        @NotNull Node memberAnchor,
        @NotNull String methodName,
        @Nullable String receiverValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public CallableLoadItem {
        Objects.requireNonNull(memberAnchor, "memberAnchor must not be null");
        methodName = StringUtil.requireNonBlank(methodName, "methodName");
        if (receiverValueId != null) {
            receiverValueId = FrontendCfgGraph.validateValueId(receiverValueId, "receiverValueId");
        }
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
        return receiverValueId == null ? List.of() : List.of(receiverValueId);
    }
}
