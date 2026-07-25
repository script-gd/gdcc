package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.ForStatement;
import gd.script.gdcc.frontend.lowering.ForIterationOperationDescriptor;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.type.GdCompilerType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Reads the hidden iterator state, publishes the ordinary raw element value and commits the
/// source-facing iterator local before the body statements run.
///
/// `resultValueId` is the raw element produced by the route's get operation; it is backed by a
/// standalone `cfg_tmp_*` slot typed as `getOperation().resultType()` and intentionally does not alias
/// the source-facing iterator local. Lowering converts that raw value (when required) and commits it
/// into `sourceIteratorSlotId`, whose type is the final `slotTypes()[ForStatement]` exposed type. The
/// hidden state slot is only read here and never appears as an ordinary operand value id.
///
/// The get operation descriptor is carried verbatim from `FrontendForLoweringContract.get()`.
public record ForLoopGetItem(
        @NotNull ForStatement statement,
        @NotNull ForIterationOperationDescriptor getOperation,
        @NotNull String iteratorStateSlotId,
        @NotNull String resultValueId,
        @NotNull String sourceIteratorSlotId
) implements ValueOpItem {
    public ForLoopGetItem {
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(getOperation, "getOperation must not be null");
        iteratorStateSlotId = FrontendCfgGraph.validateNodeId(iteratorStateSlotId, "iteratorStateSlotId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
        sourceIteratorSlotId = FrontendCfgGraph.validateNodeId(sourceIteratorSlotId, "sourceIteratorSlotId");
        if (getOperation.resultType() instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalArgumentException(
                    "for-in get raw element must be an ordinary type, but got compiler-only type "
                            + compilerOnlyType.getTypeName()
            );
        }
        if (sourceIteratorSlotId.equals(iteratorStateSlotId)) {
            throw new IllegalArgumentException(
                    "for-in get item must keep source iterator slot and hidden state slot distinct, but both were '"
                            + sourceIteratorSlotId + "'"
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
