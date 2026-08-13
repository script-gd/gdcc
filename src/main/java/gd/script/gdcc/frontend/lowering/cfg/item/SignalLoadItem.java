package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Receiver-qualified signal read placeholder for a published `RESOLVED` SIGNAL member.
///
/// This item is intentionally distinct from `MemberLoadItem`: a signal read constructs a fresh
/// `godot_Signal` value and must not publish a property writable route. Bare signal identifiers
/// stay on the opaque-expression path and never become a `SignalLoadItem`.
public record SignalLoadItem(
        @NotNull Node memberAnchor,
        @NotNull String signalName,
        @Nullable String receiverValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public SignalLoadItem {
        Objects.requireNonNull(memberAnchor, "memberAnchor must not be null");
        signalName = StringUtil.requireNonBlank(signalName, "signalName");
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
