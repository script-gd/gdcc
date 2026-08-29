package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// User-level `assert` statement recorded on the compile surface.
///
/// The item carries the already-built condition value id and the optional message value id, but
/// publishes no value id of its own, so it implements `SequenceItem` directly instead of
/// `ValueOpItem` and never enters the produced-value materialization dispatch. Truthiness
/// normalization of the condition value is deferred to body lowering, which appends the final
/// `AssertInsn` after normalizing the condition into a bool slot.
public record AssertItem(
        @NotNull AssertStatement statement,
        @NotNull String conditionValueId,
        @Nullable String messageValueIdOrNull
) implements SequenceItem {
    public AssertItem {
        Objects.requireNonNull(statement, "statement must not be null");
        conditionValueId = FrontendCfgGraph.validateValueId(conditionValueId, "conditionValueId");
        if (messageValueIdOrNull != null) {
            messageValueIdOrNull = FrontendCfgGraph.validateValueId(messageValueIdOrNull, "messageValueIdOrNull");
        }
    }

    @Override
    public @NotNull AssertStatement anchor() {
        return statement;
    }
}
