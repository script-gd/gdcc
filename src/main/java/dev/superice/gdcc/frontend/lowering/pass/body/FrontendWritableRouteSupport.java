package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.lir.insn.StoreStaticInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared frontend-only lowering support for writable access routes.
///
/// This class does not own semantic analysis, AST child evaluation, or CFG publication. It consumes
/// an already-frozen route description and performs only the body-lowering work that must stay
/// shared between assignment targets, compound-assignment current-value reads, and future mutating
/// receiver writeback:
/// - materialize the leaf read
/// - execute the leaf write
/// - execute reverse commits back into outer owners
/// - expose one explicit gate hook for runtime-gated writeback
///
/// Current callers still assemble these routes from the existing body-lowering surface because CFG
/// has not published the full writable-route payload yet. The important invariant is already frozen
/// here: once a caller constructs a `FrontendWritableAccessChain`, the support must not reopen AST
/// child evaluation.
final class FrontendWritableRouteSupport {
    private FrontendWritableRouteSupport() {
    }

    static final @NotNull ReverseCommitGateHook ALWAYS_APPLY = (_, _) -> true;

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
                materializeSubscriptLeafReadInto(session, block, leaf, actualResultSlotId);
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
            case SubscriptLeaf leaf -> writeSubscriptLeaf(session, block, leaf, actualWrittenValueSlotId);
        };
    }

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
        for (var index = reverseCommitSteps.size() - 1; index >= 0; index--) {
            var step = reverseCommitSteps.get(index);
            if (!actualGateHook.shouldApply(step, actualWrittenBackValueSlotId)) {
                continue;
            }
            appendReverseCommitStep(session, block, step, actualWrittenBackValueSlotId);
        }
    }

    private static void materializeSubscriptLeafReadInto(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull SubscriptLeaf leaf,
            @NotNull String resultSlotId
    ) {
        if (leaf.memberNameOrNull() == null) {
            FrontendSubscriptInsnSupport.appendLoad(
                    block,
                    resultSlotId,
                    leaf.baseOrReceiverSlotId(),
                    leaf.keySlotId(),
                    leaf.accessKind()
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
                leaf.keySlotId(),
                leaf.accessKind()
        );
    }

    private static @NotNull String writeSubscriptLeaf(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull SubscriptLeaf leaf,
            @NotNull String writtenValueSlotId
    ) {
        if (leaf.memberNameOrNull() == null) {
            FrontendSubscriptInsnSupport.appendStore(
                    block,
                    leaf.baseOrReceiverSlotId(),
                    leaf.keySlotId(),
                    writtenValueSlotId,
                    leaf.accessKind()
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
                leaf.keySlotId(),
                writtenValueSlotId,
                leaf.accessKind()
        );
        block.appendNonTerminatorInstruction(new VariantSetNamedInsn(
                leaf.baseOrReceiverSlotId(),
                scratch.nameSlotId(),
                scratch.namedBaseSlotId()
        ));
        return leaf.baseOrReceiverSlotId();
    }

    private static void appendReverseCommitStep(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull FrontendWritableCommitStep step,
            @NotNull String writtenBackValueSlotId
    ) {
        switch (step) {
            case InstancePropertyCommitStep propertyStep -> block.appendNonTerminatorInstruction(new StorePropertyInsn(
                    propertyStep.propertyName(),
                    propertyStep.receiverSlotId(),
                    writtenBackValueSlotId
            ));
            case StaticPropertyCommitStep propertyStep -> block.appendNonTerminatorInstruction(new StoreStaticInsn(
                    propertyStep.receiverTypeName(),
                    propertyStep.propertyName(),
                    writtenBackValueSlotId
            ));
            case SubscriptCommitStep subscriptStep -> {
                if (subscriptStep.memberNameOrNull() == null) {
                    FrontendSubscriptInsnSupport.appendStore(
                            block,
                            subscriptStep.baseOrReceiverSlotId(),
                            subscriptStep.keySlotId(),
                            writtenBackValueSlotId,
                            subscriptStep.accessKind()
                    );
                    return;
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
                        subscriptStep.keySlotId(),
                        writtenBackValueSlotId,
                        subscriptStep.accessKind()
                );
                block.appendNonTerminatorInstruction(new VariantSetNamedInsn(
                        subscriptStep.baseOrReceiverSlotId(),
                        scratch.nameSlotId(),
                        scratch.namedBaseSlotId()
                ));
            }
        }
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

    @FunctionalInterface
    interface ReverseCommitGateHook {
        boolean shouldApply(
                @NotNull FrontendWritableCommitStep step,
                @NotNull String writtenBackValueSlotId
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
            @NotNull List<FrontendWritableCommitStep> reverseCommitSteps
    ) {
        FrontendWritableAccessChain {
            Objects.requireNonNull(routeAnchor, "routeAnchor must not be null");
            Objects.requireNonNull(root, "root must not be null");
            Objects.requireNonNull(leaf, "leaf must not be null");
            reverseCommitSteps = List.copyOf(Objects.requireNonNull(
                    reverseCommitSteps,
                    "reverseCommitSteps must not be null"
            ));
            reverseCommitSteps.forEach(step -> Objects.requireNonNull(step, "reverseCommitSteps must not contain null"));
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
            @Nullable String memberNameOrNull,
            @NotNull String keySlotId,
            @NotNull FrontendSubscriptInsnSupport.SubscriptAccessKind accessKind,
            @NotNull GdType valueType
    ) implements FrontendWritableLeaf {
        SubscriptLeaf {
            baseOrReceiverSlotId = StringUtil.requireNonBlank(baseOrReceiverSlotId, "baseOrReceiverSlotId");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
            keySlotId = StringUtil.requireNonBlank(keySlotId, "keySlotId");
            Objects.requireNonNull(accessKind, "accessKind must not be null");
            Objects.requireNonNull(valueType, "valueType must not be null");
        }
    }

    /// One outward writeback step executed after the direct leaf mutation has completed.
    ///
    /// `FrontendWritableCommitStep` exists because mutating the leaf is often not enough. For
    /// value-semantic chains such as `self.payloads[i] = rhs`, the indexed store mutates the inner
    /// container first, then that mutated container must be written back into `self.payloads`, and
    /// possibly further outward if the chain is longer.
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
    /// assuming the base was already a standalone slot.
    record SubscriptCommitStep(
            @NotNull String baseOrReceiverSlotId,
            @Nullable String memberNameOrNull,
            @NotNull String keySlotId,
            @NotNull FrontendSubscriptInsnSupport.SubscriptAccessKind accessKind
    ) implements FrontendWritableCommitStep {
        SubscriptCommitStep {
            baseOrReceiverSlotId = StringUtil.requireNonBlank(baseOrReceiverSlotId, "baseOrReceiverSlotId");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
            keySlotId = StringUtil.requireNonBlank(keySlotId, "keySlotId");
            Objects.requireNonNull(accessKind, "accessKind must not be null");
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
