package gd.script.gdcc.frontend.lowering.cfg.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Executing sequence item that may publish a frontend-local value id.
///
/// These items are the bridge between straight-line source execution and later control-flow nodes:
/// `SequenceNode`s store them in order, and later `BranchNode`s / `StopNode`s reference the value ids
/// they publish instead of re-walking AST subtrees. Items that exist only for source anchoring, such
/// as `pass`, must use `SourceAnchorItem` so execution semantics remain explicit.
/// Most value ids remain single-definition, but merge-result slots are intentionally different:
/// one outward-facing result id may be written by multiple `MergeValueItem`s on mutually-exclusive
/// paths. Any code that collects producers for a value id must therefore handle multiple producers
/// instead of assuming a unique reverse lookup.
///
/// Not every published value id materializes into one `cfg_tmp_*` variable:
/// - ordinary producers still lower into temp-backed slots
/// - merge producers write one shared `cfg_merge_*` slot
/// - the direct-slot alias item intentionally keeps a value id bound to one trusted source slot so
///   call lowering can consume it without inventing a dead temp first
public sealed interface ValueOpItem extends SequenceItem permits OpaqueExprValueItem, DirectSlotAliasValueItem,
        LocalDeclarationItem, AssignmentItem, CompoundAssignmentBinaryOpItem, MemberLoadItem, SignalLoadItem,
        CallableLoadItem, StandaloneCallableLoadItem, SubscriptLoadItem, LambdaConstructItem,
        CallItem, CastItem, TypeTestItem, MergeValueItem, BoolConstantItem, ForLoopInitItem,
        ForLoopShouldContinueItem, ForLoopGetItem, ForLoopNextItem, ContainerLiteralItem {
    /// Result value id published by this item, or `null` when the item only commits state.
    @Nullable String resultValueIdOrNull();

    /// Already-materialized operand ids consumed by this item in source evaluation order.
    @NotNull List<String> operandValueIds();

    /// Whether this item's published result is backed by one standalone lowering-owned slot.
    ///
    /// `true` means later passes can expect this item to own one standalone materialization slot by
    /// default. Only the narrow non-slot-backed exceptions override this:
    /// - `DirectSlotAliasValueItem` reuses one trusted source slot instead of allocating a new slot
    /// - `AssignmentItem` returns `false` when it is statement-shaped and publishes no result value
    default boolean hasStandaloneMaterializationSlot() {
        return true;
    }
}
