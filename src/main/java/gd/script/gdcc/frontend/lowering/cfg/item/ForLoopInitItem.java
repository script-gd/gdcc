package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.ForStatement;
import gd.script.gdcc.frontend.lowering.ForIterationOperationDescriptor;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Initializes the hidden loop-carried iterator state for one `for-in` loop.
///
/// The item consumes the already-materialized source operand value ids (the `range(...)` arguments
/// for RANGE_CALL, or the single stop operand for INT_SHORTHAND) and writes the route's init
/// operation result into the dedicated hidden iterator state slot. It publishes no ordinary result
/// value: the hidden state slot is lowering-owned mutable storage, not a CFG value id, so it never
/// enters the value producer map or ordinary operand surface.
///
/// The init operation descriptor is carried verbatim from `FrontendForLoweringContract.init()` so
/// lowering emits the contracted intrinsic without re-querying the route or hardcoding its name.
public record ForLoopInitItem(
        @NotNull ForStatement statement,
        @NotNull ForIterationOperationDescriptor initOperation,
        @NotNull List<String> operandValueIds,
        @NotNull String iteratorStateSlotId
) implements ValueOpItem {
    public ForLoopInitItem {
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(initOperation, "initOperation must not be null");
        operandValueIds = List.copyOf(Objects.requireNonNull(operandValueIds, "operandValueIds must not be null"));
        for (var operandValueId : operandValueIds) {
            FrontendCfgGraph.validateValueId(operandValueId, "operandValueId");
        }
        iteratorStateSlotId = FrontendCfgGraph.validateNodeId(iteratorStateSlotId, "iteratorStateSlotId");
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
        return operandValueIds;
    }

    @Override
    public boolean hasStandaloneMaterializationSlot() {
        return false;
    }
}
