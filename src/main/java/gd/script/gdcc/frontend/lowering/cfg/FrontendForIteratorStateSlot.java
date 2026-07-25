package gd.script.gdcc.frontend.lowering.cfg;

import dev.superice.gdparser.frontend.ast.ForStatement;
import gd.script.gdcc.type.GdCompilerType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Hidden loop-carried iterator state slot published for one compile-ready `ForStatement`.
///
/// This is lowering-owned mutable storage for the iteration state (for example the range iterator
/// cursor), not a source expression value. It is discovered, typed and declared through a path that
/// is fully separate from the source-facing iterator slot: the state type comes from
/// `FrontendForLoweringContract.iteratorStateType()` and may only appear in hidden-slot metadata,
/// hidden LIR locals, intrinsic operand/result and backend C storage.
///
/// The slot id and the next-commit temp id are slot references, never CFG value ids: they must not
/// enter the value producer map, ordinary operand surfaces or `cfg_tmp_*` / `cfg_merge_*`
/// materialization collection.
///
/// @param statement      owning `ForStatement`; also the registry key, identity-equal across plan, region,
///                  the four for-loop items and this slot
/// @param slotId         hidden state slot id, fixed namespace `cfg_for_iter_<n>` with `<n>` assigned by source
///               traversal order within one executable-body build; unique across nested/sibling loops
/// @param nextTempSlotId distinct temp used to commit the `next` result before assigning it back into
///                       `slotId`, fixed namespace `cfg_for_iter_next_<n>`; always different from `slotId`
/// @param stateType      compiler-only storage type; must equal the owning route's
///                  `FrontendForLoweringContract.iteratorStateType()`
public record FrontendForIteratorStateSlot(
        @NotNull ForStatement statement,
        @NotNull String slotId,
        @NotNull String nextTempSlotId,
        @NotNull GdCompilerType stateType
) {
    public FrontendForIteratorStateSlot {
        Objects.requireNonNull(statement, "statement must not be null");
        slotId = FrontendCfgGraph.validateNodeId(slotId, "slotId");
        nextTempSlotId = FrontendCfgGraph.validateNodeId(nextTempSlotId, "nextTempSlotId");
        Objects.requireNonNull(stateType, "stateType must not be null");
        if (slotId.equals(nextTempSlotId)) {
            throw new IllegalArgumentException(
                    "Hidden for-in state slot and next temp slot must use distinct ids, but both were '"
                            + slotId + "'"
            );
        }
    }
}
