package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class FrontendCfgNodeInsnLoweringProcessors {
    private FrontendCfgNodeInsnLoweringProcessors() {
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<FrontendCfgGraph.NodeDef, Void> createRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "frontend CFG node",
                new FrontendSequenceNodeInsnLoweringProcessor(),
                new FrontendBranchNodeInsnLoweringProcessor(),
                new FrontendStopNodeInsnLoweringProcessor()
        );
    }

    /// Replays one already-built linear CFG sequence into a single LIR basic block.
    ///
    /// The processor is intentionally narrow: it only walks the published `SequenceItem` list in
    /// order and then wires the block to the single lexical continuation encoded by `nextId`.
    private static final class FrontendSequenceNodeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<FrontendCfgGraph.SequenceNode, Void> {
        @Override
        public @NotNull Class<FrontendCfgGraph.SequenceNode> nodeType() {
            return FrontendCfgGraph.SequenceNode.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendCfgGraph.SequenceNode node,
                @Nullable Void context
        ) {
            for (var item : node.items()) {
                session.lowerSequenceItem(block, item);
            }
            block.setTerminator(new GotoInsn(node.nextId()));
        }
    }

    /// Normalizes one frontend branch node into the bool-only LIR branch contract.
    ///
    /// Frontend CFG may still carry source-typed condition values, so this processor is the single
    /// place allowed to insert `pack_variant` / `unpack_variant` truthiness normalization before
    /// emitting the final `GoIfInsn`.
    private static final class FrontendBranchNodeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<FrontendCfgGraph.BranchNode, Void> {
        @Override
        public @NotNull Class<FrontendCfgGraph.BranchNode> nodeType() {
            return FrontendCfgGraph.BranchNode.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendCfgGraph.BranchNode node,
                @Nullable Void context
        ) {
            emitConditionBranch(
                    session,
                    block,
                    node.conditionValueId(),
                    session.requireValueType(node.conditionValueId()),
                    node.trueTargetId(),
                    node.falseTargetId()
            );
        }

        private void emitConditionBranch(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull String conditionValueId,
                @NotNull GdType conditionType,
                @NotNull String trueBlockId,
                @NotNull String falseBlockId
        ) {
            var sourceSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(conditionValueId);
            session.ensureVariable(sourceSlotId, conditionType);
            if (conditionType instanceof GdBoolType) {
                block.setTerminator(new GoIfInsn(sourceSlotId, trueBlockId, falseBlockId));
                return;
            }

            if (conditionType instanceof GdVariantType) {
                var boolSlotId = FrontendBodyLoweringSupport.conditionBoolSlotId(conditionValueId);
                session.ensureVariable(boolSlotId, GdBoolType.BOOL);
                block.appendNonTerminatorInstruction(new UnpackVariantInsn(boolSlotId, sourceSlotId));
                block.setTerminator(new GoIfInsn(boolSlotId, trueBlockId, falseBlockId));
                return;
            }

            var variantSlotId = FrontendBodyLoweringSupport.conditionVariantSlotId(conditionValueId);
            var boolSlotId = FrontendBodyLoweringSupport.conditionBoolSlotId(conditionValueId);
            session.ensureVariable(variantSlotId, GdVariantType.VARIANT);
            session.ensureVariable(boolSlotId, GdBoolType.BOOL);
            block.appendNonTerminatorInstruction(new PackVariantInsn(variantSlotId, sourceSlotId));
            block.appendNonTerminatorInstruction(new UnpackVariantInsn(boolSlotId, variantSlotId));
            block.setTerminator(new GoIfInsn(boolSlotId, trueBlockId, falseBlockId));
        }
    }

    /// Finishes one CFG stop node by wiring the already-materialized return slot into `ReturnInsn`.
    ///
    /// Stop nodes never inspect source AST again; they only consume the published return value id,
    /// materialize the ordinary return-slot `Variant` boundary when needed, and then emit the
    /// final `ReturnInsn`.
    private static final class FrontendStopNodeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<FrontendCfgGraph.StopNode, Void> {
        @Override
        public @NotNull Class<FrontendCfgGraph.StopNode> nodeType() {
            return FrontendCfgGraph.StopNode.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendCfgGraph.StopNode node,
                @Nullable Void context
        ) {
            var returnValueId = node.returnValueIdOrNull();
            if (returnValueId == null) {
                block.setTerminator(new ReturnInsn(null));
                return;
            }
            var materializedReturnSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    session.slotIdForValue(returnValueId),
                    session.requireValueType(returnValueId),
                    session.targetFunction().getReturnType(),
                    "return_value"
            );
            block.setTerminator(new ReturnInsn(materializedReturnSlotId));
        }
    }
}
