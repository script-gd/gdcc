package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.insn.GoIfInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
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

    /// Replays one already-built linear CFG sequence starting from the node's entry LIR basic block.
    ///
    /// The processor is intentionally narrow: it only walks the published `SequenceItem` list in
    /// order, threading through any synthetic continuation blocks that item lowering may allocate,
    /// and finally wires the last active block to the single lexical continuation encoded by
    /// `nextId`.
    private static final class FrontendSequenceNodeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<FrontendCfgGraph.SequenceNode, Void> {
        @Override
        public @NotNull Class<FrontendCfgGraph.SequenceNode> nodeType() {
            return FrontendCfgGraph.SequenceNode.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendCfgGraph.SequenceNode node,
                @Nullable Void context
        ) {
            var currentBlock = block;
            for (var item : node.items()) {
                currentBlock = session.lowerSequenceItem(currentBlock, item);
            }
            currentBlock.setTerminator(new GotoInsn(node.nextId()));
            return currentBlock;
        }
    }

    /// Normalizes one frontend branch node into the bool-only LIR branch contract.
    ///
    /// Frontend CFG may still carry source-typed condition values. Truthiness normalization itself
    /// lives in the shared `FrontendBodyLoweringSupport.materializeTruthinessToBool` helper (also
    /// consumed by assert lowering); this processor only owns the final `GoIfInsn` terminator.
    private static final class FrontendBranchNodeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<FrontendCfgGraph.BranchNode, Void> {
        @Override
        public @NotNull Class<FrontendCfgGraph.BranchNode> nodeType() {
            return FrontendCfgGraph.BranchNode.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendCfgGraph.BranchNode node,
                @Nullable Void context
        ) {
            var boolSlotId = FrontendBodyLoweringSupport.materializeTruthinessToBool(
                    session,
                    block,
                    node.conditionValueId(),
                    session.requireValueType(node.conditionValueId())
            );
            block.setTerminator(new GoIfInsn(boolSlotId, node.trueTargetId(), node.falseTargetId()));
            return block;
        }
    }

    /// Finishes one real CFG return stop by wiring the already-materialized return slot into
    /// `ReturnInsn`.
    ///
    /// Synthetic terminal-merge anchors are frontend-only structure markers and must be removed
    /// before this stage creates real LIR basic blocks. Reaching one here indicates a CFG/body
    /// lowering contract violation.
    private static final class FrontendStopNodeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<FrontendCfgGraph.StopNode, Void> {
        @Override
        public @NotNull Class<FrontendCfgGraph.StopNode> nodeType() {
            return FrontendCfgGraph.StopNode.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendCfgGraph.StopNode node,
                @Nullable Void context
        ) {
            if (node.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE) {
                throw new IllegalStateException(
                        "Synthetic terminal-merge stop node must not be lowered into a LIR basic block: " + node.id()
                );
            }
            var returnValueId = node.returnValueIdOrNull();
            if (returnValueId == null) {
                block.setTerminator(new ReturnInsn(null));
                return block;
            }
            var materializedReturnSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    session.slotIdForValue(returnValueId),
                    session.requireValueType(returnValueId),
                    session.targetFunction().getReturnType(),
                    "return_value"
            );
            block.setTerminator(new ReturnInsn(materializedReturnSlotId));
            return block;
        }
    }
}
