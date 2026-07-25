package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.ForStatement;
import gd.script.gdcc.frontend.lowering.ForIterationOperationDescriptor;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.type.GdBoolType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Reads the hidden iterator state and publishes the ordinary `bool` loop condition.
///
/// The published `resultValueId` is a single-definition CFG value consumed by the loop condition
/// branch; because the route's should-continue operation already returns `bool`, the branch tests it
/// directly without compiler-only condition normalization. The hidden state slot is only read here, it
/// is referenced through `iteratorStateSlotId` and never appears as an ordinary operand value id.
///
/// The should-continue operation descriptor is carried verbatim from
/// `FrontendForLoweringContract.shouldContinue()`.
public record ForLoopShouldContinueItem(
        @NotNull ForStatement statement,
        @NotNull ForIterationOperationDescriptor shouldContinueOperation,
        @NotNull String iteratorStateSlotId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public ForLoopShouldContinueItem {
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(shouldContinueOperation, "shouldContinueOperation must not be null");
        iteratorStateSlotId = FrontendCfgGraph.validateNodeId(iteratorStateSlotId, "iteratorStateSlotId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
        if (!(shouldContinueOperation.resultType() instanceof GdBoolType)) {
            throw new IllegalArgumentException(
                    "for-in should-continue result must be bool, but got "
                            + shouldContinueOperation.resultType().getTypeName()
            );
        }
    }

    @Override
    public @NotNull ForStatement anchor() {
        return statement;
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
