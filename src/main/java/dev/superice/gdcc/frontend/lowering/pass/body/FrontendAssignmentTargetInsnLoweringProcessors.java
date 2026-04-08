package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.*;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
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

    /// This registry is assignment-only.
    /// Writable receiver chains used by mutating calls must be lowered from one frozen route
    /// payload, not replayed through per-step item lowering, otherwise the call path loses the
    /// owner provenance needed for reverse commit.
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
    /// writability or read/write usage semantics beyond the facts already produced upstream. Any
    /// `Variant` boundary needed by the target slot is materialized before the final store emits.
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
            var materializedRhsSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    actualContext.rhsSlotId(),
                    session.requireValueType(item.rhsValueId()),
                    session.requireBindingAssignmentTargetType(binding),
                    binding.kind() == dev.superice.gdcc.frontend.sema.FrontendBindingKind.PROPERTY
                            ? "store_property"
                            : "assign_slot"
            );
            var chain = switch (binding.kind()) {
                case LOCAL_VAR, PARAMETER, CAPTURE -> new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                        node,
                        new FrontendWritableRouteSupport.FrontendWritableRoot(
                                "binding slot",
                                binding.symbolName(),
                                session.requireBindingAssignmentTargetType(binding)
                        ),
                        new FrontendWritableRouteSupport.DirectSlotLeaf(
                                binding.symbolName(),
                                session.requireBindingAssignmentTargetType(binding)
                        ),
                        List.of()
                );
                case PROPERTY -> {
                    var propertyType = session.requireBindingAssignmentTargetType(binding);
                    if (session.isStaticPropertyBinding(binding)) {
                        yield new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                                node,
                                new FrontendWritableRouteSupport.FrontendWritableRoot(
                                        "static property target",
                                        null,
                                        propertyType
                                ),
                                new FrontendWritableRouteSupport.StaticPropertyLeaf(
                                        session.currentClassName(),
                                        binding.symbolName(),
                                        propertyType
                                ),
                                List.of()
                        );
                    }
                    session.requireSelfSlot();
                    yield new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                            node,
                            new FrontendWritableRouteSupport.FrontendWritableRoot(
                                    "self property target",
                                    "self",
                                    session.requireFunctionVariableType("self")
                            ),
                            new FrontendWritableRouteSupport.InstancePropertyLeaf(
                                    "self",
                                    binding.symbolName(),
                                    propertyType
                            ),
                            List.of()
                    );
                }
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "identifier assignment binding kind is not supported: " + binding.kind()
                );
            };
            var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, materializedRhsSlotId);
            FrontendWritableRouteSupport.reverseCommit(
                    session,
                    block,
                    chain,
                    carrierSlotId,
                    FrontendWritableRouteSupport.ALWAYS_APPLY
            );
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

    /// Commits a direct subscript store through the shared writable-route support.
    ///
    /// The processor translates the published base/key operands into one route description and lets
    /// the shared helper perform both the leaf mutation and any required reverse commit, including
    /// the property-backed carrier case that previously relied on an ad-hoc write-back patch.
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
            var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                    item.anchor(),
                    new FrontendWritableRouteSupport.FrontendWritableRoot(
                            "subscript target base",
                            baseSlotId,
                            session.requireValueType(baseValueId)
                    ),
                    new FrontendWritableRouteSupport.SubscriptLeaf(
                            baseSlotId,
                            null,
                            keySlotId,
                            FrontendSubscriptInsnSupport.determineAccessKind(
                                    session.requireValueType(baseValueId),
                                    session.requireValueType(keyValueId)
                            ),
                            session.requireValueType(baseValueId)
                    ),
                    propertyCommitStepsIfNeeded(session, node.base())
            );
            var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(
                    session,
                    block,
                    chain,
                    actualContext.rhsSlotId()
            );
            FrontendWritableRouteSupport.reverseCommit(
                    session,
                    block,
                    chain,
                    carrierSlotId,
                    FrontendWritableRouteSupport.ALWAYS_APPLY
            );
        }
    }

    /// Emits the final property or static-store route for an attribute-chain assignment tail.
    ///
    /// Only the last step is interpreted here; the receiver object/type and all prior chain
    /// evaluation were already materialized into `targetOperandValueIds` by CFG build. The final
    /// property slot type comes from the published member fact, so the processor can materialize
    /// the ordinary `Variant` boundary without reopening member resolution.
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
            var materializedRhsSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    actualContext.rhsSlotId(),
                    session.requireValueType(item.rhsValueId()),
                    Objects.requireNonNull(resolvedMember.resultType(), "resultType must not be null"),
                    "store_property"
            );
            var chain = switch (resolvedMember.receiverKind()) {
                case INSTANCE -> new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                        node,
                        new FrontendWritableRouteSupport.FrontendWritableRoot(
                                "attribute receiver",
                                receiverSlotId,
                                session.requireValueType(item.targetOperandValueIds().getFirst())
                        ),
                        new FrontendWritableRouteSupport.InstancePropertyLeaf(
                                receiverSlotId,
                                node.name(),
                                Objects.requireNonNull(resolvedMember.resultType(), "resultType must not be null")
                        ),
                        List.of()
                );
                case TYPE_META -> new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                        node,
                        new FrontendWritableRouteSupport.FrontendWritableRoot(
                                "type-meta property target",
                                null,
                                Objects.requireNonNull(resolvedMember.receiverType(), "receiverType must not be null")
                        ),
                        new FrontendWritableRouteSupport.StaticPropertyLeaf(
                                session.requireStaticReceiverName(resolvedMember.receiverType()),
                                node.name(),
                                Objects.requireNonNull(resolvedMember.resultType(), "resultType must not be null")
                        ),
                        List.of()
                );
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "attribute property assignment receiver kind is not lowering-ready: "
                                + resolvedMember.receiverKind()
                );
            };
            var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(session, block, chain, materializedRhsSlotId);
            FrontendWritableRouteSupport.reverseCommit(
                    session,
                    block,
                    chain,
                    carrierSlotId,
                    FrontendWritableRouteSupport.ALWAYS_APPLY
            );
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
            var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                    node,
                    new FrontendWritableRouteSupport.FrontendWritableRoot(
                            "attribute-subscript receiver",
                            receiverSlotId,
                            session.requireValueType(item.targetOperandValueIds().getFirst())
                    ),
                    new FrontendWritableRouteSupport.SubscriptLeaf(
                            receiverSlotId,
                            node.name(),
                            keySlotId,
                            FrontendSubscriptInsnSupport.determineAccessKind(
                                    GdVariantType.VARIANT,
                                    session.requireValueType(keyValueId)
                            ),
                            GdVariantType.VARIANT
                    ),
                    List.of()
            );
            var carrierSlotId = FrontendWritableRouteSupport.writeLeaf(
                    session,
                    block,
                    chain,
                    actualContext.rhsSlotId()
            );
            FrontendWritableRouteSupport.reverseCommit(
                    session,
                    block,
                    chain,
                    carrierSlotId,
                    FrontendWritableRouteSupport.ALWAYS_APPLY
            );
        }
    }

    private static @NotNull List<FrontendWritableRouteSupport.FrontendWritableCommitStep> propertyCommitStepsIfNeeded(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull Expression baseExpression
    ) {
        if (!(baseExpression instanceof IdentifierExpression identifierExpression)) {
            return List.of();
        }
        var binding = session.requireBinding(identifierExpression);
        if (binding.kind() != dev.superice.gdcc.frontend.sema.FrontendBindingKind.PROPERTY) {
            return List.of();
        }
        if (session.isStaticPropertyBinding(binding)) {
            return List.of(new FrontendWritableRouteSupport.StaticPropertyCommitStep(
                    session.currentClassName(),
                    binding.symbolName()
            ));
        }
        session.requireSelfSlot();
        return List.of(new FrontendWritableRouteSupport.InstancePropertyCommitStep(
                "self",
                binding.symbolName()
        ));
    }

    private static @NotNull FrontendBodyLoweringSession.AssignmentTargetLoweringContext requireContext(
            @Nullable FrontendBodyLoweringSession.AssignmentTargetLoweringContext context
    ) {
        return Objects.requireNonNull(context, "context must not be null");
    }
}
