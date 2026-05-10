package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.lowering.FrontendWritableTypeWritebackSupport;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.GoIfInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.lir.insn.StoreStaticInsn;
import gd.script.gdcc.lir.insn.VariantGetNamedInsn;
import gd.script.gdcc.lir.insn.VariantSetNamedInsn;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Shared frontend-only lowering support for writable access routes.
///
/// This class is the single body-lowering core for "mutate some inner writable place, then maybe
/// write the updated carrier back into outer owners". The same mechanical workflow is needed by:
/// - assignment targets
/// - compound-assignment read/modify/write routes
/// - mutating call receivers that will later need post-call writeback
///
/// The support deliberately consumes only a frozen `FrontendWritableAccessChain`. It does not own:
/// - semantic analysis
/// - AST child evaluation
/// - CFG item publication
/// - call/assignment legality decisions
///
/// Once a caller constructs the chain, this support must treat it as the only truth source and stay
/// out of AST replay. That constraint is what keeps writeback behavior aligned across assignment and
/// future mutating-call lowering instead of growing separate ad-hoc patches.
///
/// Two reverse-commit entrypoints intentionally coexist:
/// - `reverseCommit(..., ReverseCommitGateHook)` is the compile-time gate path. It stays in one
///   block and can only answer "apply this layer or skip it" statically.
/// - `reverseCommitWithRuntimeGate(...)` is the runtime-gated path. It still reuses the same carrier
///   threading and static family shortcut, but when the current carrier is `Variant` it lets the
///   caller emit a runtime bool condition and splices one per-layer branch shape into the LIR CFG.
final class FrontendWritableRouteSupport {
    private FrontendWritableRouteSupport() {
    }

    /// Trivial gate used by routes that must always apply every reverse-commit layer.
    static final @NotNull ReverseCommitGateHook ALWAYS_APPLY = (_, _) -> true;

    /// Creates the static writeback gate from the current carrier slot type.
    ///
    /// The family matrix itself lives in the public `frontend.lowering` helper so assignment
    /// lowering, call writeback, and tests cannot silently drift into separate copies.
    static @NotNull ReverseCommitGateHook createStaticCarrierWritebackGate(
            @NotNull FrontendBodyLoweringSession session
    ) {
        Objects.requireNonNull(session, "session must not be null");
        return (_, currentCarrierSlotId) -> FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                session.requireFunctionVariableType(currentCarrierSlotId)
        );
    }

    static @NotNull String materializeLeafRead(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull String purpose
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var actualChain = Objects.requireNonNull(chain, "chain must not be null");
        var actualPurpose = StringUtil.requireNonBlank(purpose, "purpose");
        return materializeLeafReadInto(
                session,
                block,
                actualChain,
                session.allocateWritableRouteTemp(actualPurpose + "_leaf", actualChain.leaf().valueType())
        );
    }

    static @NotNull String materializeLeafReadInto(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull String resultSlotId
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var actualChain = Objects.requireNonNull(chain, "chain must not be null");
        var actualResultSlotId = StringUtil.requireNonBlank(resultSlotId, "resultSlotId");
        return switch (actualChain.leaf()) {
            case DirectSlotLeaf leaf -> leaf.slotId();
            case InstancePropertyLeaf leaf -> {
                block.appendNonTerminatorInstruction(new LoadPropertyInsn(
                        actualResultSlotId,
                        leaf.propertyName(),
                        leaf.receiverSlotId()
                ));
                yield actualResultSlotId;
            }
            case StaticPropertyLeaf leaf -> {
                block.appendNonTerminatorInstruction(new LoadStaticInsn(
                        actualResultSlotId,
                        leaf.receiverTypeName(),
                        leaf.propertyName()
                ));
                yield actualResultSlotId;
            }
            case SubscriptLeaf leaf -> {
                materializeSubscriptLeafReadInto(session, block, actualChain, leaf, actualResultSlotId);
                yield actualResultSlotId;
            }
        };
    }

    /// Writes one already-materialized value into the leaf described by `chain`.
    ///
    /// The returned slot id is the carrier that now contains the mutated leaf owner and therefore
    /// must be used for any following reverse-commit steps.
    static @NotNull String writeLeaf(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull String writtenValueSlotId
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var actualChain = Objects.requireNonNull(chain, "chain must not be null");
        var actualWrittenValueSlotId = StringUtil.requireNonBlank(writtenValueSlotId, "writtenValueSlotId");
        return switch (actualChain.leaf()) {
            case DirectSlotLeaf leaf -> {
                block.appendNonTerminatorInstruction(new AssignInsn(leaf.slotId(), actualWrittenValueSlotId));
                yield leaf.slotId();
            }
            case InstancePropertyLeaf leaf -> {
                block.appendNonTerminatorInstruction(new StorePropertyInsn(
                        leaf.propertyName(),
                        leaf.receiverSlotId(),
                        actualWrittenValueSlotId
                ));
                yield leaf.receiverSlotId();
            }
            case StaticPropertyLeaf leaf -> {
                block.appendNonTerminatorInstruction(new StoreStaticInsn(
                        leaf.receiverTypeName(),
                        leaf.propertyName(),
                        actualWrittenValueSlotId
                ));
                yield actualWrittenValueSlotId;
            }
            case SubscriptLeaf leaf -> writeSubscriptLeaf(session, block, actualChain, leaf, actualWrittenValueSlotId);
        };
    }

    /// Walks reverse-commit steps from inner to outer owner using compile-time gate decisions only.
    ///
    /// If a gate rejects one layer, only that layer's writeback is skipped. The carrier is still
    /// promoted to the next outer owner before the walk continues so gdcc stays aligned with
    /// Godot's per-layer `JUMP_IF_SHARED` behavior.
    static void reverseCommit(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull String writtenBackValueSlotId,
            @Nullable ReverseCommitGateHook gateHook
    ) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(block, "block must not be null");
        var actualChain = Objects.requireNonNull(chain, "chain must not be null");
        var actualWrittenBackValueSlotId = StringUtil.requireNonBlank(writtenBackValueSlotId, "writtenBackValueSlotId");
        var actualGateHook = gateHook == null ? ALWAYS_APPLY : gateHook;
        var reverseCommitSteps = actualChain.reverseCommitSteps();
        var currentCarrierSlotId = actualWrittenBackValueSlotId;
        for (var index = reverseCommitSteps.size() - 1; index >= 0; index--) {
            var step = reverseCommitSteps.get(index);
            var terminalStep = index == 0;
            if (!actualGateHook.shouldApply(step, currentCarrierSlotId)) {
                currentCarrierSlotId = nextOuterCarrierSlotId(step, currentCarrierSlotId, terminalStep);
                continue;
            }
            currentCarrierSlotId = appendReverseCommitStep(
                    session,
                    block,
                    actualChain,
                    step,
                    currentCarrierSlotId,
                    terminalStep
            );
        }
    }

    /// Walks reverse-commit steps from inner to outer owner, inserting runtime gate branches when
    /// the current carrier is statically `Variant`.
    ///
    /// This is the runtime-gated companion of the boolean-only `reverseCommit(...)` API:
    /// - statically known shared/reference carriers still use
    ///   `FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(...)` as the fast
    ///   path and skip the current layer without emitting any runtime branch
    /// - statically known value-semantic carriers still apply inline in the current block
    /// - only `Variant` carriers ask the caller to emit a runtime bool condition
    ///
    /// The returned block is the continuation block that outer lowering should keep appending to.
    /// It may be the original `block` when no runtime branch was needed, or the last synthetic
    /// post-gate block when one or more per-layer `GoIfInsn` regions were materialized.
    static @NotNull LirBasicBlock reverseCommitWithRuntimeGate(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull String writtenBackValueSlotId,
            @NotNull ReverseCommitRuntimeGateEmitter runtimeGateEmitter
    ) {
        Objects.requireNonNull(session, "session must not be null");
        var currentBlock = Objects.requireNonNull(block, "block must not be null");
        var actualChain = Objects.requireNonNull(chain, "chain must not be null");
        var currentCarrierSlotId = StringUtil.requireNonBlank(writtenBackValueSlotId, "writtenBackValueSlotId");
        var actualRuntimeGateEmitter = Objects.requireNonNull(
                runtimeGateEmitter,
                "runtimeGateEmitter must not be null"
        );
        var reverseCommitSteps = actualChain.reverseCommitSteps();
        for (var index = reverseCommitSteps.size() - 1; index >= 0; index--) {
            var step = reverseCommitSteps.get(index);
            var terminalStep = index == 0;
            var currentCarrierType = session.requireFunctionVariableType(currentCarrierSlotId);
            if (!requiresRuntimeWritebackGate(currentCarrierType)) {
                if (!FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(currentCarrierType)) {
                    currentCarrierSlotId = nextOuterCarrierSlotId(step, currentCarrierSlotId, terminalStep);
                    continue;
                }
                currentCarrierSlotId = appendReverseCommitStep(
                        session,
                        currentBlock,
                        actualChain,
                        step,
                        currentCarrierSlotId,
                        terminalStep
                );
                continue;
            }
            var gateConditionSlotId = StringUtil.requireNonBlank(
                    actualRuntimeGateEmitter.emitShouldApplyCondition(
                            session,
                            currentBlock,
                            step,
                            currentCarrierSlotId
                    ),
                    "gateConditionSlotId"
            );
            var applyBlock = session.createWritableRouteBlock("reverse_commit_apply");
            var skipBlock = session.createWritableRouteBlock("reverse_commit_skip");
            var continueBlock = session.createWritableRouteBlock("reverse_commit_continue");
            currentBlock.setTerminator(new GoIfInsn(gateConditionSlotId, applyBlock.id(), skipBlock.id()));
            var nextCarrierSlotId = nextOuterCarrierSlotId(step, currentCarrierSlotId, terminalStep);
            var appliedCarrierSlotId = appendReverseCommitStep(
                    session,
                    applyBlock,
                    actualChain,
                    step,
                    currentCarrierSlotId,
                    terminalStep
            );
            if (!appliedCarrierSlotId.equals(nextCarrierSlotId)) {
                throw new IllegalStateException(
                        "Runtime-gated reverse commit requires applied and skipped carrier promotion to agree, but step "
                                + step.getClass().getSimpleName()
                                + " produced '"
                                + appliedCarrierSlotId
                                + "' vs '"
                                + nextCarrierSlotId
                                + "'"
                );
            }
            applyBlock.setTerminator(new GotoInsn(continueBlock.id()));
            skipBlock.setTerminator(new GotoInsn(continueBlock.id()));
            currentBlock = continueBlock;
            currentCarrierSlotId = nextCarrierSlotId;
        }
        return currentBlock;
    }

    private static void materializeSubscriptLeafReadInto(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull SubscriptLeaf leaf,
            @NotNull String resultSlotId
    ) {
        var key = materializeSubscriptKey(
                session,
                block,
                chain,
                leaf.keySlotId(),
                leaf.keyType(),
                leaf.receiverType(),
                leaf.memberNameOrNull(),
                "subscript_read_key"
        );
        if (leaf.memberNameOrNull() == null) {
            FrontendSubscriptInsnSupport.appendLoad(
                    block,
                    resultSlotId,
                    leaf.baseOrReceiverSlotId(),
                    key.slotId(),
                    key.accessKind()
            );
            return;
        }
        var scratch = materializeNamedMemberScratch(
                session,
                block,
                leaf.baseOrReceiverSlotId(),
                leaf.memberNameOrNull(),
                "leaf_read"
        );
        FrontendSubscriptInsnSupport.appendLoad(
                block,
                resultSlotId,
                scratch.namedBaseSlotId(),
                key.slotId(),
                key.accessKind()
        );
    }

    private static @NotNull String writeSubscriptLeaf(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull SubscriptLeaf leaf,
            @NotNull String writtenValueSlotId
    ) {
        var key = materializeSubscriptKey(
                session,
                block,
                chain,
                leaf.keySlotId(),
                leaf.keyType(),
                leaf.receiverType(),
                leaf.memberNameOrNull(),
                "subscript_write_key"
        );
        if (leaf.memberNameOrNull() == null) {
            FrontendSubscriptInsnSupport.appendStore(
                    block,
                    leaf.baseOrReceiverSlotId(),
                    key.slotId(),
                    writtenValueSlotId,
                    key.accessKind()
            );
            return leaf.baseOrReceiverSlotId();
        }
        var scratch = materializeNamedMemberScratch(
                session,
                block,
                leaf.baseOrReceiverSlotId(),
                leaf.memberNameOrNull(),
                "leaf_write"
        );
        FrontendSubscriptInsnSupport.appendStore(
                block,
                scratch.namedBaseSlotId(),
                key.slotId(),
                writtenValueSlotId,
                key.accessKind()
        );
        block.appendNonTerminatorInstruction(new VariantSetNamedInsn(
                leaf.baseOrReceiverSlotId(),
                scratch.nameSlotId(),
                scratch.namedBaseSlotId()
        ));
        return leaf.baseOrReceiverSlotId();
    }

    /// Emits one reverse-commit step and returns the next outer owner carrier.
    ///
    /// Multi-step routes such as `self.items[i].x += 1` require this explicit carrier threading:
    /// - writing `x` yields the mutated `element`
    /// - committing `items[i]` yields the mutated `items`
    /// - committing `self.items` then observes that updated `items` carrier
    ///
    /// Reusing the original leaf carrier for every step would silently corrupt outer writeback.
    /// Skipping one step also does not terminate the walk: Godot emits one `JUMP_IF_SHARED` region per
    /// layer, so a skipped inner writeback still promotes the carrier to the next outer owner.
    private static @NotNull String appendReverseCommitStep(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull FrontendWritableCommitStep step,
            @NotNull String writtenBackValueSlotId,
            boolean terminalStep
    ) {
        return switch (step) {
            case InstancePropertyCommitStep propertyStep -> {
                block.appendNonTerminatorInstruction(new StorePropertyInsn(
                        propertyStep.propertyName(),
                        propertyStep.receiverSlotId(),
                        writtenBackValueSlotId
                ));
                yield nextOuterCarrierSlotId(propertyStep, writtenBackValueSlotId, terminalStep);
            }
            case StaticPropertyCommitStep propertyStep -> {
                if (!terminalStep) {
                    throw new IllegalStateException(
                            "StaticPropertyCommitStep must be terminal in reverse commit, but outer steps still remain"
                    );
                }
                block.appendNonTerminatorInstruction(new StoreStaticInsn(
                        propertyStep.receiverTypeName(),
                        propertyStep.propertyName(),
                        writtenBackValueSlotId
                ));
                yield writtenBackValueSlotId;
            }
            case SubscriptCommitStep subscriptStep -> {
                var key = materializeSubscriptKey(
                        session,
                        block,
                        chain,
                        subscriptStep.keySlotId(),
                        subscriptStep.keyType(),
                        subscriptStep.receiverType(),
                        subscriptStep.memberNameOrNull(),
                        "subscript_commit_key"
                );
                if (subscriptStep.memberNameOrNull() == null) {
                    FrontendSubscriptInsnSupport.appendStore(
                            block,
                            subscriptStep.baseOrReceiverSlotId(),
                            key.slotId(),
                            writtenBackValueSlotId,
                            key.accessKind()
                    );
                    yield nextOuterCarrierSlotId(subscriptStep, writtenBackValueSlotId, terminalStep);
                }
                var scratch = materializeNamedMemberScratch(
                        session,
                        block,
                        subscriptStep.baseOrReceiverSlotId(),
                        subscriptStep.memberNameOrNull(),
                        "reverse_commit"
                );
                FrontendSubscriptInsnSupport.appendStore(
                        block,
                        scratch.namedBaseSlotId(),
                        key.slotId(),
                        writtenBackValueSlotId,
                        key.accessKind()
                );
                block.appendNonTerminatorInstruction(new VariantSetNamedInsn(
                        subscriptStep.baseOrReceiverSlotId(),
                        scratch.nameSlotId(),
                        scratch.namedBaseSlotId()
                ));
                yield nextOuterCarrierSlotId(subscriptStep, writtenBackValueSlotId, terminalStep);
            }
        };
    }

    /// Reuses one materialized key/index slot across leaf read/write and reverse commit for the same
    /// frozen subscript route. This keeps `base[key].mutate()` from casting or unpacking `key` twice.
    ///
    /// `materializedSubscriptKeys` lives on the access chain, so it can only reuse keys materialized
    /// inside the same leaf/writeback lowering operation. Standalone subscript reads still materialize
    /// independently, which is important because they do not share a reverse-commit chain.
    private static @NotNull MaterializedSubscriptKey materializeSubscriptKey(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableAccessChain chain,
            @NotNull String sourceKeySlotId,
            @NotNull GdType sourceKeyType,
            @NotNull GdType receiverType,
            @Nullable String memberNameOrNull,
            @NotNull String boundaryUse
    ) {
        var cacheKey = new SubscriptMaterializationKey(
                sourceKeySlotId,
                sourceKeyType,
                receiverType,
                memberNameOrNull
        );
        var cached = chain.materializedSubscriptKeys().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        var materialized = session.materializeSubscriptKey(
                block,
                sourceKeySlotId,
                sourceKeyType,
                receiverType,
                memberNameOrNull,
                boundaryUse
        );
        chain.materializedSubscriptKeys().put(cacheKey, materialized);
        return materialized;
    }

    /// Computes the carrier visible to the next outer reverse-commit layer.
    ///
    /// This helper is shared by both the applied and skipped-step paths so gate semantics stay
    /// isomorphic to Godot: `JUMP_IF_SHARED` skips only the current layer's writeback block, but the
    /// compiler still promotes `assigned = info.base` before continuing with outer layers.
    private static @NotNull String nextOuterCarrierSlotId(
            @NotNull FrontendWritableCommitStep step,
            @NotNull String writtenBackValueSlotId,
            boolean terminalStep
    ) {
        return switch (step) {
            case InstancePropertyCommitStep propertyStep -> propertyStep.receiverSlotId();
            case StaticPropertyCommitStep _ -> {
                if (!terminalStep) {
                    throw new IllegalStateException(
                            "StaticPropertyCommitStep must be terminal in reverse commit, but outer steps still remain"
                    );
                }
                yield writtenBackValueSlotId;
            }
            case SubscriptCommitStep subscriptStep -> subscriptStep.baseOrReceiverSlotId();
        };
    }

    private static @NotNull NamedMemberScratch materializeNamedMemberScratch(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull String receiverSlotId,
            @NotNull String memberName,
            @NotNull String purpose
    ) {
        var actualPurpose = StringUtil.requireNonBlank(purpose, "purpose");
        var nameSlotId = session.allocateWritableRouteTemp(actualPurpose + "_named_key", GdStringNameType.STRING_NAME);
        var namedBaseSlotId = session.allocateWritableRouteTemp(actualPurpose + "_named_base", GdVariantType.VARIANT);
        block.appendNonTerminatorInstruction(new LiteralStringNameInsn(
                nameSlotId,
                StringUtil.requireNonBlank(memberName, "memberName")
        ));
        block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                namedBaseSlotId,
                StringUtil.requireNonBlank(receiverSlotId, "receiverSlotId"),
                nameSlotId
        ));
        return new NamedMemberScratch(nameSlotId, namedBaseSlotId);
    }

    /// The dynamic gate path only needs runtime branching for carriers whose writeback requirement
    /// cannot be decided from the static family alone.
    ///
    /// At the moment that means `Variant` and only `Variant`: shared/reference families are skipped
    /// by `FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(...)`, while
    /// concrete value-semantic families apply inline with no extra branch. The runtime helper is
    /// therefore a narrow completion of the static rule rather than a second, unrelated writeback
    /// policy.
    private static boolean requiresRuntimeWritebackGate(@NotNull GdType currentCarrierType) {
        return Objects.requireNonNull(currentCarrierType, "currentCarrierType must not be null") instanceof GdVariantType;
    }

    @FunctionalInterface
    interface ReverseCommitGateHook {
        /// `writtenBackValueSlotId` is the current carrier about to be written into this step.
        /// Returning false skips only the current step's writeback. The carrier is still promoted to
        /// the next outer owner so later layers can keep checking their own runtime gate, matching
        /// Godot's per-layer `JUMP_IF_SHARED` regions.
        boolean shouldApply(
                @NotNull FrontendWritableCommitStep step,
                @NotNull String writtenBackValueSlotId
        );
    }

    @FunctionalInterface
    interface ReverseCommitRuntimeGateEmitter {
        /// Emits one bool condition into `block` for a runtime-open carrier and returns the slot id
        /// consumed by the per-layer `GoIfInsn`.
        ///
        /// The emitter is intentionally narrow:
        /// - it answers only the runtime-open branch (`Variant` today)
        /// - it does not own carrier threading or reverse-commit structure
        /// - it must not reopen AST or re-decide whether outer layers exist
        ///
        /// This keeps the shared support as the owner of the writeback walk while leaving the
        /// actual bool materialization open to future helper calls such as
        /// `gdcc_variant_requires_writeback(...)`.
        @NotNull String emitShouldApplyCondition(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull FrontendWritableCommitStep step,
                @NotNull String currentCarrierSlotId
        );
    }

    /// One writable route frozen for body lowering.
    ///
    /// The route intentionally splits into three layers:
    /// - `root`: where this writable chain originates conceptually
    /// - `leaf`: the concrete place that this lowering step reads or mutates directly
    /// - `reverseCommitSteps`: how the mutated leaf value must be written back into outer owners
    ///
    /// This split is the core design choice of the shared support. Assignment targets, subscript
    /// writes, and future mutating-receiver calls all need the same workflow:
    /// 1. materialize the current leaf value
    /// 2. mutate that leaf
    /// 3. walk outward and commit the new value back into enclosing owners
    ///
    /// If these three concerns were collapsed into one flat structure, the support would have to
    /// re-derive "what is the real leaf?" and "what still needs writeback?" on every call site.
    /// Keeping them explicit makes the lowering contract mechanical and prevents ad-hoc writeback
    /// patches from growing separately in assignment and call paths.
    record FrontendWritableAccessChain(
            @NotNull Node routeAnchor,
            @NotNull FrontendWritableRoot root,
            @NotNull FrontendWritableLeaf leaf,
            @NotNull List<FrontendWritableCommitStep> reverseCommitSteps,
            @NotNull Map<SubscriptMaterializationKey, MaterializedSubscriptKey> materializedSubscriptKeys
    ) {
        FrontendWritableAccessChain(
                @NotNull Node routeAnchor,
                @NotNull FrontendWritableRoot root,
                @NotNull FrontendWritableLeaf leaf,
                @NotNull List<FrontendWritableCommitStep> reverseCommitSteps
        ) {
            this(routeAnchor, root, leaf, reverseCommitSteps, new HashMap<>());
        }

        FrontendWritableAccessChain {
            Objects.requireNonNull(routeAnchor, "routeAnchor must not be null");
            Objects.requireNonNull(root, "root must not be null");
            Objects.requireNonNull(leaf, "leaf must not be null");
            reverseCommitSteps = List.copyOf(Objects.requireNonNull(
                    reverseCommitSteps,
                    "reverseCommitSteps must not be null"
            ));
            reverseCommitSteps.forEach(step -> Objects.requireNonNull(step, "reverseCommitSteps must not contain null"));
            materializedSubscriptKeys = new HashMap<>(Objects.requireNonNull(
                    materializedSubscriptKeys,
                    "materializedSubscriptKeys must not be null"
            ));
        }
    }

    private record SubscriptMaterializationKey(
            @NotNull String sourceKeySlotId,
            @NotNull GdType sourceKeyType,
            @NotNull GdType receiverType,
            @Nullable String memberNameOrNull
    ) {
        private SubscriptMaterializationKey {
            sourceKeySlotId = StringUtil.requireNonBlank(sourceKeySlotId, "sourceKeySlotId");
            Objects.requireNonNull(sourceKeyType, "sourceKeyType must not be null");
            Objects.requireNonNull(receiverType, "receiverType must not be null");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
        }
    }

    /// Describes the conceptual owner at which a writable route starts.
    ///
    /// `FrontendWritableRoot` is deliberately *not* the same thing as the leaf:
    /// - the root answers "what object/slot/type does this route belong to?"
    /// - the leaf answers "what exact place do we read/write right now?"
    ///
    /// Examples:
    /// - `values[i] = rhs`
    ///   - root: the slot holding `values`
    ///   - leaf: the indexed element `values[i]`
    /// - `self.payloads[i] = rhs`
    ///   - root: the outer receiver/property route rooted at `self`
    ///   - leaf: the indexed element inside `self.payloads`
    ///
    /// The support currently does not use `root` to emit instructions directly. It exists so the
    /// route still carries provenance even when the leaf is several levels away from the original
    /// owner. That provenance becomes important for diagnostics, invariants, and future CFG-payload
    /// publication where lowering must consume a frozen route instead of reconstructing intent.
    record FrontendWritableRoot(
            @NotNull String description,
            @Nullable String slotIdOrNull,
            @NotNull GdType type
    ) {
        FrontendWritableRoot {
            description = StringUtil.requireNonBlank(description, "description");
            if (slotIdOrNull != null) {
                slotIdOrNull = StringUtil.requireNonBlank(slotIdOrNull, "slotIdOrNull");
            }
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    /// The concrete writable/readable place that this lowering step touches directly.
    ///
    /// `FrontendWritableLeaf` is the pivot between "read current value" and "commit new value":
    /// - `materializeLeafRead*` looks only at the leaf to load the current value
    /// - `writeLeaf` looks only at the leaf to perform the direct mutation/store
    ///
    /// Reverse commit intentionally does *not* inspect the leaf again. Once the leaf write returns a
    /// mutated carrier slot, the remaining work is described only by `FrontendWritableCommitStep`.
    ///
    /// This separation prevents a common source of drift: if the support had to infer reverse
    /// writeback from the leaf shape, then attribute-subscript and future mutating-call cases would
    /// gradually accumulate special-case logic in multiple places.
    sealed interface FrontendWritableLeaf permits DirectSlotLeaf, InstancePropertyLeaf, StaticPropertyLeaf, SubscriptLeaf {
        @NotNull GdType valueType();
    }

    /// A leaf that is already a stable slot.
    ///
    /// No extra read materialization is needed here: the current leaf value already lives in
    /// `slotId`, and writing the leaf is just `AssignInsn(slotId, newValue)`.
    record DirectSlotLeaf(
            @NotNull String slotId,
            @NotNull GdType valueType
    ) implements FrontendWritableLeaf {
        DirectSlotLeaf {
            slotId = StringUtil.requireNonBlank(slotId, "slotId");
            Objects.requireNonNull(valueType, "valueType must not be null");
        }
    }

    /// A leaf addressed as `receiver.property`.
    ///
    /// The leaf read is `LoadPropertyInsn`, the leaf write is `StorePropertyInsn`, and the mutated
    /// carrier after a write is the receiver slot because the receiver remains the owner that may
    /// still need outer reverse-commit steps.
    record InstancePropertyLeaf(
            @NotNull String receiverSlotId,
            @NotNull String propertyName,
            @NotNull GdType valueType
    ) implements FrontendWritableLeaf {
        InstancePropertyLeaf {
            receiverSlotId = StringUtil.requireNonBlank(receiverSlotId, "receiverSlotId");
            propertyName = StringUtil.requireNonBlank(propertyName, "propertyName");
            Objects.requireNonNull(valueType, "valueType must not be null");
        }
    }

    /// A leaf addressed as `TypeName.property`.
    ///
    /// Static property writes have no enclosing runtime owner slot to carry forward, so `writeLeaf`
    /// returns the written value slot itself. This is different from instance/property/subscript
    /// leaves where the mutated owner container must be preserved for later reverse commit.
    record StaticPropertyLeaf(
            @NotNull String receiverTypeName,
            @NotNull String propertyName,
            @NotNull GdType valueType
    ) implements FrontendWritableLeaf {
        StaticPropertyLeaf {
            receiverTypeName = StringUtil.requireNonBlank(receiverTypeName, "receiverTypeName");
            propertyName = StringUtil.requireNonBlank(propertyName, "propertyName");
            Objects.requireNonNull(valueType, "valueType must not be null");
        }
    }

    /// `memberNameOrNull` freezes the attribute-subscript named-base contract:
    /// - `null` means a plain `base[key]`
    /// - non-null means `receiver.member[key]`, so the support must materialize one named-base temp
    ///   and write it back into the outer receiver as part of the same leaf operation
    record SubscriptLeaf(
            @NotNull String baseOrReceiverSlotId,
            @NotNull GdType receiverType,
            @Nullable String memberNameOrNull,
            @NotNull String keySlotId,
            @NotNull GdType keyType,
            @NotNull GdType valueType
    ) implements FrontendWritableLeaf {
        SubscriptLeaf {
            baseOrReceiverSlotId = StringUtil.requireNonBlank(baseOrReceiverSlotId, "baseOrReceiverSlotId");
            Objects.requireNonNull(receiverType, "receiverType must not be null");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
            keySlotId = StringUtil.requireNonBlank(keySlotId, "keySlotId");
            Objects.requireNonNull(keyType, "keyType must not be null");
            Objects.requireNonNull(valueType, "valueType must not be null");
        }
    }

    /// One outward writeback step executed after the direct leaf mutation has completed.
    ///
    /// `FrontendWritableCommitStep` exists because mutating the leaf is often not enough. For
    /// value-semantic chains such as `self.payloads[i] = rhs`, the indexed store mutates the inner
    /// container first, then that mutated container must be written back into `self.payloads`, and
    /// possibly further outward if the chain is longer. Each applied step therefore produces the
    /// next outer owner carrier consumed by the following reverse-commit step.
    ///
    /// This type deliberately mirrors only the operations needed for *reverse* commit:
    /// - property commit
    /// - static property commit
    /// - subscript commit
    ///
    /// It is not another "leaf" hierarchy. The leaf describes the innermost direct operation; commit
    /// steps describe the outer owners that must observe the already-mutated carrier value.
    sealed interface FrontendWritableCommitStep permits
            InstancePropertyCommitStep,
            StaticPropertyCommitStep,
            SubscriptCommitStep {
    }

    /// Writes the mutated carrier back into `receiver.property`.
    record InstancePropertyCommitStep(
            @NotNull String receiverSlotId,
            @NotNull String propertyName
    ) implements FrontendWritableCommitStep {
        InstancePropertyCommitStep {
            receiverSlotId = StringUtil.requireNonBlank(receiverSlotId, "receiverSlotId");
            propertyName = StringUtil.requireNonBlank(propertyName, "propertyName");
        }
    }

    /// Writes the mutated carrier back into `TypeName.property`.
    /// This is a terminal reverse-commit step because there is no outer runtime owner slot to return.
    record StaticPropertyCommitStep(
            @NotNull String receiverTypeName,
            @NotNull String propertyName
    ) implements FrontendWritableCommitStep {
        StaticPropertyCommitStep {
            receiverTypeName = StringUtil.requireNonBlank(receiverTypeName, "receiverTypeName");
            propertyName = StringUtil.requireNonBlank(propertyName, "propertyName");
        }
    }

    /// Writes the mutated carrier back through one subscript owner layer.
    ///
    /// This is used both for plain `base[key]` routes and for attribute-subscript cases where the
    /// effective subscript base first comes from `receiver.member`. In the latter case the step keeps
    /// `memberNameOrNull` so reverse commit can rebuild the same named-base writeback shape instead of
    /// assuming the base was already a standalone slot. After the write completes, the next carrier is
    /// always the owning base/receiver slot itself.
    record SubscriptCommitStep(
            @NotNull String baseOrReceiverSlotId,
            @NotNull GdType receiverType,
            @Nullable String memberNameOrNull,
            @NotNull String keySlotId,
            @NotNull GdType keyType
    ) implements FrontendWritableCommitStep {
        SubscriptCommitStep {
            baseOrReceiverSlotId = StringUtil.requireNonBlank(baseOrReceiverSlotId, "baseOrReceiverSlotId");
            Objects.requireNonNull(receiverType, "receiverType must not be null");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
            keySlotId = StringUtil.requireNonBlank(keySlotId, "keySlotId");
            Objects.requireNonNull(keyType, "keyType must not be null");
        }
    }

    private record NamedMemberScratch(
            @NotNull String nameSlotId,
            @NotNull String namedBaseSlotId
    ) {
        private NamedMemberScratch {
            nameSlotId = StringUtil.requireNonBlank(nameSlotId, "nameSlotId");
            namedBaseSlotId = StringUtil.requireNonBlank(namedBaseSlotId, "namedBaseSlotId");
        }
    }
}
