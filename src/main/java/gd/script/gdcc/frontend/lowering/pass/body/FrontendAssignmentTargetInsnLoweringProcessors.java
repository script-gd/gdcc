package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.lir.LirBasicBlock;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

final class FrontendAssignmentTargetInsnLoweringProcessors {
    private FrontendAssignmentTargetInsnLoweringProcessors() {
    }

    /// Lowers one published assignment target exclusively from its frozen writable-route payload.
    ///
    /// Assignment/compound-assignment store lowering no longer replays AST tail steps:
    /// - CFG publication already froze the owner/leaf/reverse-commit route on `AssignmentItem`
    /// - that payload is now mandatory for every lowering-ready assignment target
    /// - body lowering may still use `targetOperandValueIds` for earlier sequencing/current-value reads
    /// - but the final store itself must consume only the payload, otherwise legacy ad-hoc writeback
    ///   patches could coexist with shared reverse commit and silently double-store
    /// - runtime-gated reverse commit may splice blocks, so the returned block is the active continuation
    static @NotNull LirBasicBlock lowerPublishedWritableRoute(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull AssignmentItem item,
            @NotNull String rhsSlotId
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var actualItem = Objects.requireNonNull(item, "item must not be null");
        var payload = actualItem.writableRoutePayload();
        var chain = session.requireWritableAccessChain(payload);
        var materializedRhsSlotId = session.materializeFrontendBoundaryValue(
                block,
                Objects.requireNonNull(rhsSlotId, "rhsSlotId must not be null"),
                session.requireValueType(actualItem.rhsValueId()),
                chain.leaf().valueType(),
                writeLeafPurpose(chain.leaf())
        );
        var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, materializedRhsSlotId);
        return FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(
                session,
                block,
                chain,
                carrierSlotId,
                (_, gateBlock, _, currentCarrierSlotId) ->
                        FrontendSequenceItemInsnLoweringProcessors.emitVariantRequiresWritebackCondition(
                                session,
                                gateBlock,
                                currentCarrierSlotId
                        )
        );
    }

    private static @NotNull String writeLeafPurpose(@NotNull FrontendWritableRouteSupport.FrontendWritableLeaf leaf) {
        return switch (Objects.requireNonNull(leaf, "leaf must not be null")) {
            case FrontendWritableRouteSupport.DirectSlotLeaf _ -> "assign_slot";
            case FrontendWritableRouteSupport.InstancePropertyLeaf _,
                 FrontendWritableRouteSupport.DynamicPropertyLeaf _,
                 FrontendWritableRouteSupport.StaticPropertyLeaf _ -> "store_property";
            case FrontendWritableRouteSupport.SubscriptLeaf _ -> "store_subscript";
        };
    }

}
