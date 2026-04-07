package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CastItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.CallStaticMethodInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.lir.insn.LineNumberInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.type.GdVariantType;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

final class FrontendSequenceItemInsnLoweringProcessors {
    private FrontendSequenceItemInsnLoweringProcessors() {
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<SequenceItem, Void> createRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "sequence item",
                new FrontendSourceAnchorInsnLoweringProcessor(),
                new FrontendLocalDeclarationInsnLoweringProcessor(),
                new FrontendBoolConstantInsnLoweringProcessor(),
                new FrontendMergeValueInsnLoweringProcessor(),
                new FrontendOpaqueExprValueInsnLoweringProcessor(),
                new FrontendCallInsnLoweringProcessor(),
                new FrontendMemberLoadInsnLoweringProcessor(),
                new FrontendSubscriptLoadInsnLoweringProcessor(),
                new FrontendCompoundAssignmentBinaryInsnLoweringProcessor(),
                new FrontendAssignmentInsnLoweringProcessor(),
                new FrontendCastInsnLoweringProcessor(),
                new FrontendTypeTestInsnLoweringProcessor()
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SourceAnchorItem node,
                @Nullable Void context
        ) {
            var lineNumber = sourceLine(node.statement());
            if (lineNumber > 0) {
                block.appendNonTerminatorInstruction(new LineNumberInsn(lineNumber));
            }
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull LocalDeclarationItem node,
                @Nullable Void context
        ) {
            var initializerValueId = node.initializerValueIdOrNull();
            if (initializerValueId == null) {
                return;
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull BoolConstantItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new LiteralBoolInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.value()
            ));
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MergeValueItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.mergeSlotId(node.resultValueId()),
                    session.slotIdForValue(node.sourceValueId())
            ));
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem node,
                @Nullable Void context
        ) {
            var policy = classifyOpaqueExpression(node.expression());
            if (policy.handling() != OpaqueExprHandling.HANDLE_NOW) {
                throw session.unsupportedSequenceItem(node, policy.detail());
            }
            session.lowerOpaqueExpression(block, node);
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

    /// Emits call instructions strictly from the published call route.
    ///
    /// The processor is not allowed to repair missing receiver information, redo overload choice,
    /// or guess between global/static/instance call families once compile-ready facts exist. It
    /// only materializes the already-approved argument-side `Variant` boundaries required by the
    /// selected callable signature for exact routes. Runtime-open `DYNAMIC_FALLBACK` instance calls
    /// reuse the ordinary `CallMethodInsn` surface and forward their already-evaluated operands.
    /// Dynamic dispatch stays on the backend route; later typed consumers of the published
    /// `Variant` result still use the ordinary frontend boundary helper.
    private static final class FrontendCallInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CallItem, Void> {
        @Override
        public @NotNull Class<CallItem> nodeType() {
            return CallItem.class;
        }

        @Override
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @Nullable Void context
        ) {
            var resolvedCall = session.requireResolvedCall(node.anchor());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            switch (resolvedCall.callKind()) {
                case INSTANCE_METHOD -> block.appendNonTerminatorInstruction(new CallMethodInsn(
                        resultSlotId,
                        resolvedCall.callableName(),
                        session.resolveInstanceCallReceiver(node),
                        arguments
                ));
                case STATIC_METHOD -> {
                    if (resolvedCall.receiverType() == null) {
                        block.appendNonTerminatorInstruction(new CallGlobalInsn(
                                resultSlotId,
                                resolvedCall.callableName(),
                                arguments
                        ));
                        return;
                    }
                    block.appendNonTerminatorInstruction(new CallStaticMethodInsn(
                            resultSlotId,
                            session.requireStaticReceiverName(resolvedCall.receiverType()),
                            resolvedCall.callableName(),
                            arguments
                    ));
                }
                case CONSTRUCTOR -> {
                    var constructorResultType = Objects.requireNonNull(
                            resolvedCall.returnType(),
                            "resolved constructor call must carry a result type"
                    );
                    switch (constructorResultType) {
                        // Builtin/container constructors materialize directly from the published call route.
                        case dev.superice.gdcc.type.GdObjectType _ ->
                                block.appendNonTerminatorInstruction(new ConstructObjectInsn(
                                        resultSlotId,
                                        session.requireClassName(constructorResultType)
                                ));
                        default ->
                                block.appendNonTerminatorInstruction(new ConstructBuiltinInsn(resultSlotId, arguments));
                    }
                }
                case DYNAMIC_FALLBACK -> {
                    if (resolvedCall.receiverKind() != FrontendReceiverKind.INSTANCE) {
                        throw session.unsupportedSequenceItem(
                                node,
                                "dynamic call lowering requires an instance receiver route, but got "
                                        + resolvedCall.receiverKind()
                        );
                    }
                    block.appendNonTerminatorInstruction(new CallMethodInsn(
                            resultSlotId,
                            resolvedCall.callableName(),
                            session.resolveInstanceCallReceiver(node),
                            arguments
                    ));
                }
                case UNKNOWN -> throw session.unsupportedSequenceItem(
                        node,
                        "call route is not lowering-ready: " + resolvedCall.callKind()
                );
            }
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MemberLoadItem node,
                @Nullable Void context
        ) {
            var resolvedMember = session.requireResolvedMember(node.anchor());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            switch (resolvedMember.receiverKind()) {
                case INSTANCE -> {
                    if (node.baseValueIdOrNull() == null) {
                        throw session.unsupportedSequenceItem(
                                node,
                                "instance member load is missing a receiver value id"
                        );
                    }
                    block.appendNonTerminatorInstruction(new LoadPropertyInsn(
                            resultSlotId,
                            node.memberName(),
                            session.slotIdForValue(node.baseValueIdOrNull())
                    ));
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
        public void lower(
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
            if (node.memberNameOrNull() == null) {
                FrontendSubscriptInsnSupport.appendLoad(
                        block,
                        FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                        baseSlotId,
                        session.requireValueType(node.baseValueId()),
                        keySlotId,
                        keyType
                );
                return;
            }

            var namedBaseSlotId = session.namedBaseSlotId(node.resultValueId());
            var nameSlotId = session.namedKeySlotId(node.resultValueId());
            block.appendNonTerminatorInstruction(new LiteralStringNameInsn(nameSlotId, node.memberNameOrNull()));
            block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                    namedBaseSlotId,
                    baseSlotId,
                    nameSlotId
            ));
            FrontendSubscriptInsnSupport.appendLoad(
                    block,
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    namedBaseSlotId,
                    GdVariantType.VARIANT,
                    keySlotId,
                    keyType
            );
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
        public void lower(
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem node,
                @Nullable Void context
        ) {
            var rhsSlotId = session.slotIdForValue(node.rhsValueId());
            session.lowerAssignmentTarget(block, node, rhsSlotId);
            if (node.resultValueIdOrNull() != null) {
                block.appendNonTerminatorInstruction(new AssignInsn(
                        FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueIdOrNull()),
                        rhsSlotId
                ));
            }
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
        public void lower(
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
        public void lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull TypeTestItem node,
                @Nullable Void context
        ) {
            throw session.unsupportedSequenceItem(node, "type-test lowering is not implemented yet");
        }
    }
}
