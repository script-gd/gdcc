package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import gd.script.gdcc.frontend.lowering.FrontendCallMutabilitySupport;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopGetItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopInitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopNextItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopShouldContinueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

final class FrontendSequenceItemInsnLoweringProcessors {
    private FrontendSequenceItemInsnLoweringProcessors() {
    }

    /// Emits the runtime-open writeback predicate for one current carrier slot.
    /// The helper name and positive `requires_writeback` polarity are part of the frozen
    /// frontend/backend contract and must stay aligned with `gdcc_helper.h`.
    static @NotNull String emitVariantRequiresWritebackCondition(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull String currentCarrierSlotId
    ) {
        var gateSlotId = session.allocateWritableRouteTemp("variant_requires_writeback", GdBoolType.BOOL);
        block.appendNonTerminatorInstruction(new CallGlobalInsn(
                gateSlotId,
                "gdcc_variant_requires_writeback",
                List.of(new LirInstruction.VariableOperand(currentCarrierSlotId))
        ));
        return gateSlotId;
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<SequenceItem, Void> createRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "sequence item",
                new FrontendSourceAnchorInsnLoweringProcessor(),
                new FrontendLocalDeclarationInsnLoweringProcessor(),
                new FrontendBoolConstantInsnLoweringProcessor(),
                new FrontendMergeValueInsnLoweringProcessor(),
                new FrontendOpaqueExprValueInsnLoweringProcessor(),
                new FrontendDirectSlotAliasInsnLoweringProcessor(),
                new FrontendCallInsnLoweringProcessor(),
                new FrontendMemberLoadInsnLoweringProcessor(),
                new FrontendSubscriptLoadInsnLoweringProcessor(),
                new FrontendCompoundAssignmentBinaryInsnLoweringProcessor(),
                new FrontendAssignmentInsnLoweringProcessor(),
                new FrontendCastInsnLoweringProcessor(),
                new FrontendTypeTestInsnLoweringProcessor(),
                new FrontendForLoopInitInsnLoweringProcessor(),
                new FrontendForLoopShouldContinueInsnLoweringProcessor(),
                new FrontendForLoopGetInsnLoweringProcessor(),
                new FrontendForLoopNextInsnLoweringProcessor()
        );
    }

    /// Emits source line markers without touching value flow or runtime state.
    ///
    /// Source anchors exist only to preserve debug location fidelity for later passes and tooling,
    /// so the processor appends `LineNumberInsn` only when the statement still carries a concrete
    /// source range.
    private static final class FrontendSourceAnchorInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SourceAnchorItem, Void> {
        @Override
        public @NotNull Class<SourceAnchorItem> nodeType() {
            return SourceAnchorItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SourceAnchorItem node,
                @Nullable Void context
        ) {
            var lineNumber = sourceLine(node.statement());
            if (lineNumber > 0) {
                block.appendNonTerminatorInstruction(new LineNumberInsn(lineNumber));
            }
            return block;
        }

        private int sourceLine(@NotNull Statement statement) {
            var range = statement.range();
            return range == null ? -1 : range.startPoint().row() + 1;
        }
    }

    /// Commits a published local initializer into the stable source-local slot.
    ///
    /// Slot declaration itself already happened before instruction lowering. The remaining job here
    /// is to materialize any ordinary `Variant` boundary required by the published local slot type
    /// and then commit the final value into the stable source-local storage.
    private static final class FrontendLocalDeclarationInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<LocalDeclarationItem, Void> {
        @Override
        public @NotNull Class<LocalDeclarationItem> nodeType() {
            return LocalDeclarationItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull LocalDeclarationItem node,
                @Nullable Void context
        ) {
            var initializerValueId = node.initializerValueIdOrNull();
            if (initializerValueId == null) {
                return block;
            }
            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    session.slotIdForValue(initializerValueId),
                    session.requireValueType(initializerValueId),
                    session.requireSourceLocalSlotType(node.declaration()),
                    "local_init"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.sourceLocalSlotId(node.declaration()),
                    materializedSlotId
            ));
            return block;
        }
    }

    /// Materializes one frontend bool constant item into a dedicated temp slot.
    ///
    /// Bool constants intentionally keep their own CFG item instead of flowing through the generic
    /// literal route when short-circuit lowering needs path-local truth values.
    private static final class FrontendBoolConstantInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<BoolConstantItem, Void> {
        @Override
        public @NotNull Class<BoolConstantItem> nodeType() {
            return BoolConstantItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull BoolConstantItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new LiteralBoolInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.value()
            ));
            return block;
        }
    }

    /// Moves one mutually-exclusive merge source into the shared merge slot.
    ///
    /// Merge items are the only legal multi-producer value ids in frontend CFG, so the processor
    /// always writes into `cfg_merge_<valueId>` rather than pretending the value still has a unique
    /// SSA-like temp slot.
    private static final class FrontendMergeValueInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MergeValueItem, Void> {
        @Override
        public @NotNull Class<MergeValueItem> nodeType() {
            return MergeValueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MergeValueItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.mergeSlotId(node.resultValueId()),
                    session.slotIdForValue(node.sourceValueId())
            ));
            return block;
        }
    }

    /// Gates the generic opaque-expression route before handing off to expression-root processors.
    ///
    /// Only leaf values plus eager unary/binary operators are allowed through this surface. Any
    /// short-circuit, deferred, or special-form expression must already have been published as a
    /// dedicated CFG item before body lowering starts.
    private static final class FrontendOpaqueExprValueInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<OpaqueExprValueItem, Void> {
        @Override
        public @NotNull Class<OpaqueExprValueItem> nodeType() {
            return OpaqueExprValueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem node,
                @Nullable Void context
        ) {
            var policy = classifyOpaqueExpression(node.expression());
            if (policy.handling() != OpaqueExprHandling.HANDLE_NOW) {
                throw session.unsupportedSequenceItem(node, policy.detail());
            }
            return session.lowerOpaqueExpression(block, node);
        }

        private @NotNull OpaqueExprPolicy classifyOpaqueExpression(@NotNull Expression expression) {
            return switch (expression) {
                case IdentifierExpression _, LiteralExpression _, SelfExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "leaf values stay on the OpaqueExprValueItem route"
                );
                case UnaryExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "eager unary expressions still lower from OpaqueExprValueItem"
                );
                case BinaryExpression binaryExpression when isShortCircuitBinary(binaryExpression) ->
                        new OpaqueExprPolicy(
                                OpaqueExprHandling.REJECT,
                                "short-circuit and/or must lower through branchy control flow, not BinaryOpInsn"
                        );
                case BinaryExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "eager binary operators keep using OpaqueExprValueItem"
                );
                case ConditionalExpression _, CastExpression _, TypeTestExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.DEFER,
                        "this expression root needs a dedicated lowering route before body pass consumes it"
                );
                case ArrayExpression _, DictionaryExpression _, PreloadExpression _, GetNodeExpression _ ->
                        new OpaqueExprPolicy(
                                OpaqueExprHandling.DEFER,
                                "this compile-blocked expression family stays outside the first body lowering surface"
                        );
                case AssignmentExpression _, AttributeExpression _, CallExpression _, SubscriptExpression _ ->
                        new OpaqueExprPolicy(
                                OpaqueExprHandling.REJECT,
                                "this expression must not re-enter the generic opaque fallback path"
                        );
                default -> new OpaqueExprPolicy(
                        OpaqueExprHandling.REJECT,
                        "unsupported opaque expression root: " + expression.getClass().getSimpleName()
                );
            };
        }

        private boolean isShortCircuitBinary(@NotNull BinaryExpression binaryExpression) {
            return switch (binaryExpression.operator()) {
                case "and", "or" -> true;
                default -> false;
            };
        }

        private enum OpaqueExprHandling {
            HANDLE_NOW,
            DEFER,
            REJECT
        }

        private record OpaqueExprPolicy(
                @NotNull OpaqueExprHandling handling,
                @NotNull String detail
        ) {
            private OpaqueExprPolicy {
                Objects.requireNonNull(handling, "handling must not be null");
                detail = Objects.requireNonNull(detail, "detail must not be null");
            }
        }
    }

    /// Direct-slot alias values are publication-only. They intentionally reuse one trusted source slot
    /// and therefore emit no standalone instruction or temp declaration during body lowering.
    private static final class FrontendDirectSlotAliasInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<DirectSlotAliasValueItem, Void> {
        @Override
        public @NotNull Class<DirectSlotAliasValueItem> nodeType() {
            return DirectSlotAliasValueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull DirectSlotAliasValueItem node,
                @Nullable Void context
        ) {
            session.slotIdForValue(node.resultValueId());
            return block;
        }
    }

    /// Emits call instructions strictly from the published call route.
    ///
    /// The processor is not allowed to repair missing receiver information, redo overload choice,
    /// or guess between global/static/instance call families once compile-ready facts exist. It
    /// only materializes the already-approved argument-side `Variant` boundaries required by the
    /// selected callable signature for exact routes. Runtime-open `DYNAMIC_FALLBACK` instance calls
    /// still reuse the ordinary `CallMethodInsn` surface, but the receiver side may now attach
    /// conservative runtime-gated reverse commit when a writable payload is published. The
    /// processor must therefore treat receiver writeback as one parallel contract around the same
    /// call instruction instead of re-splitting the receiver chain into ad-hoc step items here.
    /// The returned block is the active continuation block after any post-call writeback, because a
    /// dynamic receiver gate may splice synthetic `apply/skip/continue` blocks and later sequence
    /// items must keep lowering into that returned continuation.
    private static final class FrontendCallInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CallItem, Void> {
        @Override
        public @NotNull Class<CallItem> nodeType() {
            return CallItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @Nullable Void context
        ) {
            var resolvedCall = session.requireResolvedCall(node.anchor());
            return switch (resolvedCall.callKind()) {
                case INSTANCE_METHOD -> lowerExactInstanceCall(session, block, node, resolvedCall);
                case STATIC_METHOD -> lowerStaticMethodCall(session, block, node, resolvedCall);
                case CONSTRUCTOR -> lowerConstructorCall(
                        session,
                        block,
                        node,
                        resolvedCall,
                        requireMaterializedResultSlotId(node, "constructor call")
                );
                case DYNAMIC_FALLBACK -> lowerDynamicInstanceCall(
                        session,
                        block,
                        node,
                        resolvedCall,
                        requireMaterializedResultSlotId(node, "dynamic call")
                );
                case UNKNOWN -> throw session.unsupportedSequenceItem(
                        node,
                        "call route is not lowering-ready: " + resolvedCall.callKind()
                );
            };
        }

        private @NotNull LirBasicBlock lowerExactInstanceCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            var mutatingReceiverRoute = mutatingReceiverRouteOrNull(session, node, resolvedCall);
            var receiverSlotId = session.materializeCallReceiverLeaf(block, node);
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    emittedExactResultSlotIdOrNull(node, resolvedCall),
                    resolvedCall.callableName(),
                    receiverSlotId,
                    arguments
            ));
            return continueAfterReceiverWriteback(session, block, mutatingReceiverRoute, receiverSlotId);
        }

        private @NotNull LirBasicBlock lowerStaticMethodCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            if (resolvedCall.receiverType() == null) {
                block.appendNonTerminatorInstruction(new CallGlobalInsn(
                        emittedExactResultSlotIdOrNull(node, resolvedCall),
                        resolvedCall.callableName(),
                        arguments
                ));
                return block;
            }
            block.appendNonTerminatorInstruction(new CallStaticMethodInsn(
                    emittedExactResultSlotIdOrNull(node, resolvedCall),
                    session.requireStaticReceiverName(resolvedCall.receiverType()),
                    resolvedCall.callableName(),
                    arguments
            ));
            return block;
        }

        private @NotNull LirBasicBlock lowerConstructorCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall,
                @NotNull String resultSlotId
        ) {
            var constructorResultType = Objects.requireNonNull(
                    resolvedCall.returnType(),
                    "resolved constructor call must carry a result type"
            );
            // Unary builtin-from-Variant construction is published as a dedicated constructor route, but
            // sema intentionally anchors its declaration site to the builtin owner instead of one concrete
            // overload. Lowering therefore must reuse the already-evaluated Variant slot directly rather
            // than asking the ordinary callable-signature materializer for a synthetic constructor shape.
            if (resolvedCall.ownerKind() == ScopeOwnerKind.BUILTIN
                    && resolvedCall.argumentTypes().size() == 1
                    && resolvedCall.argumentTypes().getFirst() instanceof GdVariantType) {
                if (node.argumentValueIds().size() != 1) {
                    throw new IllegalStateException(
                            "Builtin Variant constructor unpack route must publish exactly one argument value id"
                    );
                }
                var argumentValueId = node.argumentValueIds().getFirst();
                var argumentType = session.requireValueType(argumentValueId);
                if (!(argumentType instanceof GdVariantType)) {
                    throw new IllegalStateException(
                            "Builtin Variant constructor unpack route requires Variant argument slot, but got "
                                    + argumentType.getTypeName()
                    );
                }
                block.appendNonTerminatorInstruction(new UnpackVariantInsn(
                        resultSlotId,
                        session.slotIdForValue(argumentValueId)
                ));
                return block;
            }
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            switch (constructorResultType) {
                // Builtin/container constructors materialize directly from the published call route.
                case GdObjectType _ -> block.appendNonTerminatorInstruction(new ConstructObjectInsn(
                        resultSlotId,
                        session.requireClassName(constructorResultType)
                ));
                default -> block.appendNonTerminatorInstruction(new ConstructBuiltinInsn(resultSlotId, arguments));
            }
            return block;
        }

        private @NotNull LirBasicBlock lowerDynamicInstanceCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall,
                @NotNull String resultSlotId
        ) {
            if (resolvedCall.receiverKind() != FrontendReceiverKind.INSTANCE) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic call lowering requires an instance receiver route, but got "
                                + resolvedCall.receiverKind()
                );
            }
            var mutatingReceiverRoute = mutatingReceiverRouteOrNull(session, node, resolvedCall);
            var receiverSlotId = session.materializeCallReceiverLeaf(block, node);
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    resultSlotId,
                    resolvedCall.callableName(),
                    receiverSlotId,
                    arguments
            ));
            return continueAfterDynamicReceiverWriteback(session, block, mutatingReceiverRoute, receiverSlotId);
        }

        /// Exact call routes may legally omit a result slot only for resolved-void statement calls.
        /// Any non-void exact route that reaches body lowering without a published result id is still
        /// an invariant violation instead of a signal to silently drop the value.
        private @Nullable String emittedExactResultSlotIdOrNull(
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            if (resolvedCall.returnType() instanceof GdVoidType) {
                return null;
            }
            return requireMaterializedResultSlotId(node, "non-void exact call '" + resolvedCall.callableName() + "'");
        }

        private @NotNull String requireMaterializedResultSlotId(
                @NotNull CallItem node,
                @NotNull String contractDetail
        ) {
            var resultValueId = node.resultValueIdOrNull();
            if (resultValueId == null) {
                throw new IllegalStateException(contractDetail + " must publish resultValueIdOrNull before body lowering");
            }
            return FrontendBodyLoweringSupport.cfgTempSlotId(resultValueId);
        }

        /// Returns the block that later lowering must keep appending to after post-call receiver
        /// writeback. Exact routes use the shared static gate and therefore stay in-place.
        private @NotNull LirBasicBlock continueAfterReceiverWriteback(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @Nullable FrontendWritableRouteSupport.FrontendWritableAccessChain mutatingReceiverRoute,
                @NotNull String receiverSlotId
        ) {
            if (mutatingReceiverRoute == null) {
                return block;
            }
            FrontendWritableRouteSupport.reverseCommit(
                    session,
                    block,
                    mutatingReceiverRoute,
                    receiverSlotId,
                    FrontendWritableRouteSupport.createStaticCarrierWritebackGate(session)
            );
            return block;
        }

        /// Dynamic instance calls cannot answer receiver writeback from static constness alone, so
        /// lowering reuses the shared runtime gate helper and keeps lowering on the returned
        /// continuation block. The runtime helper only answers the `Variant` branch; concrete carrier
        /// families still keep using the shared static fast path inside writable-route support.
        private @NotNull LirBasicBlock continueAfterDynamicReceiverWriteback(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @Nullable FrontendWritableRouteSupport.FrontendWritableAccessChain mutatingReceiverRoute,
                @NotNull String receiverSlotId
        ) {
            if (mutatingReceiverRoute == null) {
                return block;
            }
            return FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(
                    session,
                    block,
                    mutatingReceiverRoute,
                    receiverSlotId,
                    (_, gateBlock, _, currentCarrierSlotId) -> emitVariantRequiresWritebackCondition(
                            session,
                            gateBlock,
                            currentCarrierSlotId
                    )
            );
        }

        /// Receiver writeback is enabled only for already-published writable receiver routes whose
        /// call route is conservative may-mutate. Exact routes use declaration constness when
        /// available; dynamic instance routes count as may-mutate because runtime-open dispatch has
        /// no reliable constness fact and must not let a potentially mutating receiver collapse back
        /// to plain snapshot semantics.
        private @Nullable FrontendWritableRouteSupport.FrontendWritableAccessChain mutatingReceiverRouteOrNull(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            if (!FrontendCallMutabilitySupport.mayMutateReceiver(resolvedCall)
                    || node.writableRoutePayloadOrNull() == null) {
                return null;
            }
            return session.requireWritableAccessChain(node.writableRoutePayloadOrNull());
        }

    }

    /// Emits one property/static-member read from the published member-resolution result.
    ///
    /// Member receiver kind is already frozen by semantic analysis, so the processor only maps it
    /// to the concrete load instruction family and never re-inspects the original chain. Builtin
    /// instance fields such as `vector.x` therefore lower through the same `LoadPropertyInsn`
    /// contract as engine/GDCC ordinary property reads.
    private static final class FrontendMemberLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MemberLoadItem, Void> {
        @Override
        public @NotNull Class<MemberLoadItem> nodeType() {
            return MemberLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MemberLoadItem node,
                @Nullable Void context
        ) {
            var resolvedMember = session.requireResolvedMember(node.anchor());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            if (resolvedMember.status() == FrontendMemberResolutionStatus.DYNAMIC) {
                lowerDynamicMemberLoad(session, block, node, resolvedMember, resultSlotId);
                return block;
            }
            switch (resolvedMember.receiverKind()) {
                case INSTANCE -> {
                    if (node.baseValueIdOrNull() == null) {
                        throw session.unsupportedSequenceItem(
                                node,
                                "instance member load is missing a receiver value id"
                        );
                    }
                    var receiverSlotId = session.slotIdForValue(node.baseValueIdOrNull());
                    var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                            node.anchor(),
                            new FrontendWritableRouteSupport.FrontendWritableRoot(
                                    "member receiver",
                                    receiverSlotId,
                                    session.requireValueType(node.baseValueIdOrNull())
                            ),
                            new FrontendWritableRouteSupport.InstancePropertyLeaf(
                                    receiverSlotId,
                                    node.memberName(),
                                    session.requireValueType(node.resultValueId())
                            ),
                            List.of()
                    );
                    FrontendWritableRouteSupport.materializeLeafReadInto(
                            session,
                            block,
                            chain,
                            resultSlotId
                    );
                }
                case TYPE_META -> {
                    if (node.baseValueIdOrNull() != null) {
                        throw session.unsupportedSequenceItem(
                                node,
                                "type-meta static member load must not carry a receiver value id"
                        );
                    }
                    block.appendNonTerminatorInstruction(new LoadStaticInsn(
                            resultSlotId,
                            session.requireStaticReceiverName(resolvedMember.receiverType()),
                            node.memberName()
                    ));
                }
                default -> throw session.unsupportedSequenceItem(
                        node,
                        "member receiver kind is not lowering-ready: " + resolvedMember.receiverKind()
                );
            }
            return block;
        }

        private void lowerDynamicMemberLoad(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MemberLoadItem node,
                @NotNull FrontendResolvedMember resolvedMember,
                @NotNull String resultSlotId
        ) {
            // Dynamic member reads are selected by the published status, not by receiver family.
            // Object-family receivers are only packed after the DYNAMIC route fact is already frozen.
            if (resolvedMember.receiverKind() != FrontendReceiverKind.INSTANCE) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic member load requires an instance receiver route, but got "
                                + resolvedMember.receiverKind()
                );
            }
            if (node.baseValueIdOrNull() == null) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic member load is missing a receiver value id"
                );
            }

            var receiverValueId = node.baseValueIdOrNull();
            var receiverSlotId = session.slotIdForValue(receiverValueId);
            var receiverType = session.requireValueType(receiverValueId);
            if (!(receiverType instanceof GdVariantType) && !(receiverType instanceof GdObjectType)) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic member load requires Variant or Object-family receiver, but got "
                                + receiverType.getTypeName()
                );
            }

            var receiverVariantSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    receiverSlotId,
                    receiverType,
                    GdVariantType.VARIANT,
                    "dynamic_member_read_receiver"
            );
            var nameSlotId = session.allocateWritableRouteTemp(
                    "dynamic_member_read_name",
                    GdStringNameType.STRING_NAME
            );
            block.appendNonTerminatorInstruction(new LiteralStringNameInsn(nameSlotId, node.memberName()));
            block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                    resultSlotId,
                    receiverVariantSlotId,
                    nameSlotId
            ));
        }
    }

    /// Lowers a published subscript read, including attribute-step intermediate named loads.
    ///
    /// The processor relies only on published receiver/key types to pick the final instruction
    /// family; it must not reopen subscript route inference during body materialization.
    private static final class FrontendSubscriptLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SubscriptLoadItem, Void> {
        @Override
        public @NotNull Class<SubscriptLoadItem> nodeType() {
            return SubscriptLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SubscriptLoadItem node,
                @Nullable Void context
        ) {
            session.requireSingleSubscriptArgument(node.anchor(), node.argumentValueIds());
            var baseSlotId = session.slotIdForValue(node.baseValueId());
            var keyValueId = node.argumentValueIds().getFirst();
            var keySlotId = session.slotIdForValue(keyValueId);
            var keyType = session.requireValueType(keyValueId);
            var receiverType = session.requireValueType(node.baseValueId());
            var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                    node.anchor(),
                    new FrontendWritableRouteSupport.FrontendWritableRoot(
                            node.memberNameOrNull() == null ? "subscript base" : "attribute-subscript receiver",
                            baseSlotId,
                            receiverType
                    ),
                    new FrontendWritableRouteSupport.SubscriptLeaf(
                            baseSlotId,
                            receiverType,
                            node.memberNameOrNull(),
                            keySlotId,
                            keyType,
                            session.requireValueType(node.resultValueId())
                    ),
                    List.of()
            );
            FrontendWritableRouteSupport.materializeLeafReadInto(
                    session,
                    block,
                    chain,
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId())
            );
            return block;
        }
    }

    /// Materializes the already-frozen read-modify-write computation for one compound assignment.
    ///
    /// CFG build already guaranteed evaluation order and single-evaluation target operands. This
    /// processor therefore only converts the published binary lexeme plus the two operand value ids
    /// into one `BinaryOpInsn`; it does not insert any extra assignment-boundary `(un)pack` logic.
    private static final class FrontendCompoundAssignmentBinaryInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CompoundAssignmentBinaryOpItem, Void> {
        @Override
        public @NotNull Class<CompoundAssignmentBinaryOpItem> nodeType() {
            return CompoundAssignmentBinaryOpItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CompoundAssignmentBinaryOpItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    requireCompoundOperator(session, node),
                    session.slotIdForValue(node.currentTargetValueId()),
                    session.slotIdForValue(node.rhsValueId())
            ));
            return block;
        }

        private @NotNull GodotOperator requireCompoundOperator(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull CompoundAssignmentBinaryOpItem node
        ) {
            try {
                return GodotOperator.fromSourceLexeme(
                        node.binaryOperatorLexeme(),
                        GodotOperator.OperatorArity.BINARY
                );
            } catch (IllegalArgumentException ex) {
                throw session.unsupportedSequenceItem(
                        node,
                        "compound-assignment body-lowering contract published unsupported binary operator '"
                                + node.binaryOperatorLexeme()
                                + "'"
                );
            }
        }
    }

    /// Commits one published assignment target store and optionally republishes assignment-as-value.
    ///
    /// The processor never re-evaluates target children; it delegates only on the already-frozen
    /// target shape while keeping operand ordering and RHS materialization from CFG build intact.
    private static final class FrontendAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AssignmentItem, Void> {
        @Override
        public @NotNull Class<AssignmentItem> nodeType() {
            return AssignmentItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem node,
                @Nullable Void context
        ) {
            var rhsSlotId = session.slotIdForValue(node.rhsValueId());
            var currentBlock = session.lowerAssignmentTarget(block, node, rhsSlotId);
            if (node.resultValueIdOrNull() != null) {
                currentBlock.appendNonTerminatorInstruction(new AssignInsn(
                        FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueIdOrNull()),
                        rhsSlotId
                ));
            }
            return currentBlock;
        }
    }

    /// Holds the explicit fail-fast boundary for cast items while cast lowering remains outside the
    /// current frontend-body support surface.
    ///
    /// Keeping this as its own processor keeps the unsupported route explicit in the registry instead
    /// of burying it inside an unrelated `switch`.
    private static final class FrontendCastInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CastItem, Void> {
        @Override
        public @NotNull Class<CastItem> nodeType() {
            return CastItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CastItem node,
                @Nullable Void context
        ) {
            throw session.unsupportedSequenceItem(node, "cast lowering is not implemented yet");
        }
    }

    /// Holds the explicit fail-fast boundary for type-test items until their runtime lowering contract is frozen.
    ///
    /// The dedicated processor keeps future extension localized: once type-test lowering is ready,
    /// the registry entry can be replaced without touching unrelated item handlers.
    private static final class FrontendTypeTestInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<TypeTestItem, Void> {
        @Override
        public @NotNull Class<TypeTestItem> nodeType() {
            return TypeTestItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull TypeTestItem node,
                @Nullable Void context
        ) {
            throw session.unsupportedSequenceItem(node, "type-test lowering is not implemented yet");
        }
    }

    /// Initializes the hidden loop-carried iterator state by calling the route's init intrinsic.
    ///
    /// The source operands are already materialized earlier in the init entry sequence; this processor
    /// only normalizes them into the intrinsic's `(start, end, step)` triple and writes the result into
    /// the predeclared hidden state slot. It publishes no ordinary value: the state slot is
    /// lowering-owned mutable storage, never a CFG value id.
    private static final class FrontendForLoopInitInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopInitItem, Void> {
        @Override
        public @NotNull Class<ForLoopInitItem> nodeType() {
            return ForLoopInitItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopInitItem node,
                @Nullable Void context
        ) {
            var argSlots = resolveInitArgumentSlots(session, block, node);
            var args = argSlots.stream()
                    .<LirInstruction.Operand>map(LirInstruction.VariableOperand::new)
                    .toList();
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    node.iteratorStateSlotId(),
                    node.initOperation().intrinsicName(),
                    args
            ));
            return block;
        }

        /// Resolves the init intrinsic argument slots. When the operand count already matches the
        /// contract's expected argument count (e.g. generic Variant route with a single source operand),
        /// operands pass through directly. Otherwise, range normalization pads 1..3 operands into the
        /// `(start, end, step)` triple by materializing implicit `0`/`1` int constants.
        private @NotNull List<String> resolveInitArgumentSlots(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopInitItem node
        ) {
            var operandSlots = node.operandValueIds().stream()
                    .map(session::slotIdForValue)
                    .toList();
            var expectedArgCount = node.initOperation().argumentTypes().size();
            if (operandSlots.size() == expectedArgCount) {
                return operandSlots;
            }
            return switch (operandSlots.size()) {
                case 1 -> List.of(
                        session.materializeForLoopIntConstant(block, 0L),
                        operandSlots.getFirst(),
                        session.materializeForLoopIntConstant(block, 1L)
                );
                case 2 -> List.of(
                        operandSlots.get(0),
                        operandSlots.get(1),
                        session.materializeForLoopIntConstant(block, 1L)
                );
                default -> throw new IllegalStateException(
                        "for-in init expects " + expectedArgCount + " contract arguments, but got "
                                + operandSlots.size() + " source operands"
                );
            };
        }
    }

    /// Publishes the ordinary `bool` loop condition by calling the route's should-continue intrinsic.
    ///
    /// The result lands in the predeclared `cfg_tmp_*` slot that the loop condition branch consumes
    /// directly; because the intrinsic already returns `bool`, no compiler-only condition normalization
    /// is triggered. The hidden state slot is read by reference and never appears as an ordinary operand.
    private static final class FrontendForLoopShouldContinueInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopShouldContinueItem, Void> {
        @Override
        public @NotNull Class<ForLoopShouldContinueItem> nodeType() {
            return ForLoopShouldContinueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopShouldContinueItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.shouldContinueOperation().intrinsicName(),
                    List.of(new LirInstruction.VariableOperand(node.iteratorStateSlotId()))
            ));
            return block;
        }
    }

    /// Reads the raw element from the hidden iterator state and commits the source-facing iterator local.
    ///
    /// The get intrinsic writes the raw element (typed as the contract's get result type) into a
    /// standalone `cfg_tmp_*` slot that intentionally does not alias the source local. The raw value is
    /// then converted, when required, into the final exposed iterator type through the shared frontend
    /// boundary helper and assigned into the predeclared source-facing slot before the body runs.
    private static final class FrontendForLoopGetInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopGetItem, Void> {
        @Override
        public @NotNull Class<ForLoopGetItem> nodeType() {
            return ForLoopGetItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopGetItem node,
                @Nullable Void context
        ) {
            var rawSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    rawSlotId,
                    node.getOperation().intrinsicName(),
                    List.of(new LirInstruction.VariableOperand(node.iteratorStateSlotId()))
            ));
            var sourceSlot = session.requireForSourceIteratorSlot(node.statement());
            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    rawSlotId,
                    node.getOperation().resultType(),
                    sourceSlot.exposedType(),
                    "for_in_get"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(node.sourceIteratorSlotId(), materializedSlotId));
            return block;
        }
    }

    /// Advances the hidden iterator state by calling the route's next intrinsic.
    ///
    /// The next operation returns a new state value rather than mutating in place, so the result is
    /// written into the distinct predeclared next temp first and only then committed back into the state
    /// slot via an assign. This preserves a correct lifecycle order for future destroyable generic
    /// states and keeps the intrinsic result target distinct from the state argument slot.
    private static final class FrontendForLoopNextInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopNextItem, Void> {
        @Override
        public @NotNull Class<ForLoopNextItem> nodeType() {
            return ForLoopNextItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopNextItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    node.nextTempSlotId(),
                    node.nextOperation().intrinsicName(),
                    List.of(new LirInstruction.VariableOperand(node.iteratorStateSlotId()))
            ));
            block.appendNonTerminatorInstruction(new AssignInsn(node.iteratorStateSlotId(), node.nextTempSlotId()));
            return block;
        }
    }
}
