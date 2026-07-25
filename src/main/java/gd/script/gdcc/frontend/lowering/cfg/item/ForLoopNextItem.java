package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.ForStatement;
import gd.script.gdcc.frontend.lowering.ForIterationOperationDescriptor;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Advances the hidden iterator state by running the route's next operation.
///
/// The next operation returns a new state value rather than mutating in place. Lowering therefore
/// writes that result into the distinct `nextTempSlotId` temp first and only then commits it back into
/// `iteratorStateSlotId` via an assign, preserving a correct lifecycle order for future destroyable
/// generic states. The item publishes no ordinary result value: both the state slot and the next temp
/// are hidden lowering-owned storage, never CFG value ids, so they stay out of the value producer map
/// and ordinary operand surface.
///
/// The next operation descriptor is carried verbatim from `FrontendForLoweringContract.next()`.
public record ForLoopNextItem(
        @NotNull ForStatement statement,
        @NotNull ForIterationOperationDescriptor nextOperation,
        @NotNull String iteratorStateSlotId,
        @NotNull String nextTempSlotId
) implements ValueOpItem {
    public ForLoopNextItem {
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(nextOperation, "nextOperation must not be null");
        iteratorStateSlotId = FrontendCfgGraph.validateNodeId(iteratorStateSlotId, "iteratorStateSlotId");
        nextTempSlotId = FrontendCfgGraph.validateNodeId(nextTempSlotId, "nextTempSlotId");
        if (iteratorStateSlotId.equals(nextTempSlotId)) {
            throw new IllegalArgumentException(
                    "for-in next item must keep state slot and next temp slot distinct, but both were '"
                            + iteratorStateSlotId + "'"
            );
        }
    }

    @Override
    public @NotNull ForStatement anchor() {
        return statement;
    }

    @Override
    public @Nullable String resultValueIdOrNull() {
        return null;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of();
    }

    @Override
    public boolean hasStandaloneMaterializationSlot() {
        return false;
    }
}
