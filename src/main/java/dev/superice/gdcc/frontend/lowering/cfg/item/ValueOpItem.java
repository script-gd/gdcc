package dev.superice.gdcc.frontend.lowering.cfg.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Executing sequence item that may publish a frontend-local value id.
///
/// These items are the bridge between straight-line source execution and later control-flow nodes:
/// `SequenceNode`s store them in order, and later `BranchNode`s / `StopNode`s reference the value ids
/// they publish instead of re-walking AST subtrees. Items that exist only for source anchoring, such
/// as `pass`, must use `SourceAnchorItem` so execution semantics remain explicit.
public sealed interface ValueOpItem extends SequenceItem permits OpaqueExprValueItem, LocalDeclarationItem,
        AssignmentItem, MemberLoadItem, SubscriptLoadItem, CallItem, CastItem, TypeTestItem, MergeValueItem {
    /// Result value id published by this item, or `null` when the item only commits state.
    @Nullable String resultValueIdOrNull();

    /// Already-materialized operand ids consumed by this item in source evaluation order.
    @NotNull List<String> operandValueIds();
}
