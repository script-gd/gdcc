package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.*;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

final class FrontendAssignmentTargetInsnLoweringProcessors {
    private FrontendAssignmentTargetInsnLoweringProcessors() {
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<Expression, FrontendBodyLoweringSession.AssignmentTargetLoweringContext>
    createTargetRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "assignment target",
                new FrontendIdentifierAssignmentInsnLoweringProcessor(),
                new FrontendAttributeAssignmentInsnLoweringProcessor(),
                new FrontendSubscriptAssignmentInsnLoweringProcessor()
        );
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<Node, FrontendBodyLoweringSession.AssignmentTargetLoweringContext>
    createAttributeStepRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "attribute-assignment step",
                new FrontendAttributePropertyAssignmentInsnLoweringProcessor(),
                new FrontendAttributeSubscriptAssignmentInsnLoweringProcessor()
        );
    }

    /// Writes assignment results into a bare binding-backed slot or property route.
    ///
    /// The processor only maps already-published binding kinds to runtime stores; it must not infer
    /// writability or read/write usage semantics beyond the facts already produced upstream.
    private static final class FrontendIdentifierAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<IdentifierExpression, FrontendBodyLoweringSession.AssignmentTargetLoweringContext> {
        @Override
        public @NotNull Class<IdentifierExpression> nodeType() {
            return IdentifierExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull IdentifierExpression node,
                @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
        ) {
            var actualContext = requireContext(context);
            var item = actualContext.item();
            if (!item.targetOperandValueIds().isEmpty()) {
                throw session.unsupportedSequenceItem(item, "identifier assignment must not publish target operand values");
            }
            var binding = session.requireBinding(node);
            switch (binding.kind()) {
                case LOCAL_VAR, PARAMETER, CAPTURE ->
                        block.appendNonTerminatorInstruction(new AssignInsn(binding.symbolName(), actualContext.rhsSlotId()));
                case PROPERTY -> {
                    if (session.isStaticPropertyBinding(binding)) {
                        block.appendNonTerminatorInstruction(new StoreStaticInsn(
                                session.currentClassName(),
                                binding.symbolName(),
                                actualContext.rhsSlotId()
                        ));
                        return;
                    }
                    session.requireSelfSlot();
                    block.appendNonTerminatorInstruction(new StorePropertyInsn(
                            binding.symbolName(),
                            "self",
                            actualContext.rhsSlotId()
                    ));
                }
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "identifier assignment binding kind is not supported: " + binding.kind()
                );
            }
        }
    }

    /// Delegates chained attribute assignments to a processor selected by the last published step type.
    ///
    /// CFG build already froze every receiver/key operand needed by the final write route, so this
    /// processor inspects only the tail step needed to decide whether the store is property-based
    /// or subscript-based.
    private static final class FrontendAttributeAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AttributeExpression, FrontendBodyLoweringSession.AssignmentTargetLoweringContext> {
        @Override
        public @NotNull Class<AttributeExpression> nodeType() {
            return AttributeExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AttributeExpression node,
                @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
        ) {
            var actualContext = requireContext(context);
            if (node.steps().isEmpty()) {
                throw session.unsupportedSequenceItem(
                        actualContext.item(),
                        "attribute assignment target must contain at least one step"
                );
            }
            session.lowerAttributeAssignmentStep(block, node.steps().getLast(), actualContext);
        }
    }

    /// Commits a direct subscript store using the already-published base/key operands.
    ///
    /// If the mutated base is itself a property-backed identifier, the processor also performs the
    /// required write-back so the container mutation is reflected in the owning property slot.
    private static final class FrontendSubscriptAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SubscriptExpression, FrontendBodyLoweringSession.AssignmentTargetLoweringContext> {
        @Override
        public @NotNull Class<SubscriptExpression> nodeType() {
            return SubscriptExpression.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SubscriptExpression node,
                @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
        ) {
            var actualContext = requireContext(context);
            var item = actualContext.item();
            session.requireSingleSubscriptArgument(item.anchor(), node.arguments());
            if (item.targetOperandValueIds().size() != 2) {
                throw session.unsupportedSequenceItem(item, "subscript assignment must publish base plus one key operand");
            }
            var baseValueId = item.targetOperandValueIds().getFirst();
            var keyValueId = item.targetOperandValueIds().getLast();
            var baseSlotId = session.slotIdForValue(baseValueId);
            var keySlotId = session.slotIdForValue(keyValueId);
            FrontendSubscriptInsnSupport.appendStore(
                    block,
                    baseSlotId,
                    session.requireValueType(baseValueId),
                    keySlotId,
                    session.requireValueType(keyValueId),
                    actualContext.rhsSlotId()
            );
            FrontendSubscriptInsnSupport.writeBackPropertyBaseIfNeeded(session, block, node.base(), baseSlotId);
        }
    }

    /// Emits the final property or static-store route for an attribute-chain assignment tail.
    ///
    /// Only the last step is interpreted here; the receiver object/type and all prior chain
    /// evaluation were already materialized into `targetOperandValueIds` by CFG build.
    private static final class FrontendAttributePropertyAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AttributePropertyStep, FrontendBodyLoweringSession.AssignmentTargetLoweringContext> {
        @Override
        public @NotNull Class<AttributePropertyStep> nodeType() {
            return AttributePropertyStep.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AttributePropertyStep node,
                @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
        ) {
            var actualContext = requireContext(context);
            var item = actualContext.item();
            if (item.targetOperandValueIds().size() != 1) {
                throw session.unsupportedSequenceItem(item, "attribute property assignment must publish exactly one receiver");
            }
            var receiverSlotId = session.slotIdForValue(item.targetOperandValueIds().getFirst());
            var resolvedMember = session.requireResolvedMember(node);
            switch (resolvedMember.receiverKind()) {
                case INSTANCE -> block.appendNonTerminatorInstruction(new StorePropertyInsn(
                        node.name(),
                        receiverSlotId,
                        actualContext.rhsSlotId()
                ));
                case TYPE_META -> block.appendNonTerminatorInstruction(new StoreStaticInsn(
                        session.requireClassName(resolvedMember.receiverType()),
                        node.name(),
                        actualContext.rhsSlotId()
                ));
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "attribute property assignment receiver kind is not lowering-ready: "
                                + resolvedMember.receiverKind()
                );
            }
        }
    }

    /// Handles `receiver.member[key] = rhs` by lowering the synthetic named-member load plus keyed store.
    ///
    /// The intermediate named base is always treated as `Variant`, mirroring the frontend contract
    /// that attribute-subscript steps expose only published runtime value facts and no extra static
    /// container specialization beyond the final key access choice.
    private static final class FrontendAttributeSubscriptAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AttributeSubscriptStep, FrontendBodyLoweringSession.AssignmentTargetLoweringContext> {
        @Override
        public @NotNull Class<AttributeSubscriptStep> nodeType() {
            return AttributeSubscriptStep.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AttributeSubscriptStep node,
                @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
        ) {
            var actualContext = requireContext(context);
            var item = actualContext.item();
            session.requireSingleSubscriptArgument(item.anchor(), node.arguments());
            if (item.targetOperandValueIds().size() != 2) {
                throw session.unsupportedSequenceItem(
                        item,
                        "attribute subscript assignment must publish receiver plus one key operand"
                );
            }
            var receiverSlotId = session.slotIdForValue(item.targetOperandValueIds().getFirst());
            var keyValueId = item.targetOperandValueIds().getLast();
            var keySlotId = session.slotIdForValue(keyValueId);
            var namedBaseSlotId = session.namedBaseSlotId("assign_" + item.rhsValueId());
            var nameSlotId = session.namedKeySlotId("assign_" + item.rhsValueId());
            block.appendNonTerminatorInstruction(new LiteralStringNameInsn(nameSlotId, node.name()));
            block.appendNonTerminatorInstruction(new VariantGetNamedInsn(namedBaseSlotId, receiverSlotId, nameSlotId));
            FrontendSubscriptInsnSupport.appendStore(
                    block,
                    namedBaseSlotId,
                    GdVariantType.VARIANT,
                    keySlotId,
                    session.requireValueType(keyValueId),
                    actualContext.rhsSlotId()
            );
            block.appendNonTerminatorInstruction(new VariantSetNamedInsn(receiverSlotId, nameSlotId, namedBaseSlotId));
        }
    }

    private static @NotNull FrontendBodyLoweringSession.AssignmentTargetLoweringContext requireContext(
            @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
    ) {
        return Objects.requireNonNull(context, "context must not be null");
    }
}
