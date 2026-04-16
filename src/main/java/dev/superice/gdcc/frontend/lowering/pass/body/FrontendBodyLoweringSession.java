package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdContainerType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/// Stateful carrier for one function-body lowering run.
///
/// The session owns the shared lowering facts for a single function:
/// - published semantic tables already accepted by compile gate
/// - declared temp/local/merge slots
/// - block materialization order
/// - processor registries for CFG nodes, sequence items, and opaque expressions, including
///   continuation-block threading when writable-route lowering splices synthetic blocks
/// - one dedicated writable-route entry for published assignment targets
///
/// Processors may query this session for already-frozen information, but they must not rebuild
/// semantic facts or invent alternate evaluation order.
public final class FrontendBodyLoweringSession {
    private final @NotNull FunctionLoweringContext functionContext;
    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull FrontendCfgGraph graph;
    private final @NotNull LirFunctionDef function;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull SequencedMap<String, FrontendBodyLoweringSupport.CfgValueMaterialization> valueMaterializations;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<FrontendCfgGraph.NodeDef, Void> cfgNodeProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<SequenceItem, Void> sequenceItemProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<Expression, OpaqueExprLoweringContext> opaqueExprProcessors;
    private int boundaryMaterializationCounter;
    private int writableRouteMaterializationCounter;
    private int writableRouteBlockCounter;

    public FrontendBodyLoweringSession(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull ClassRegistry classRegistry
    ) {
        this.functionContext = Objects.requireNonNull(functionContext, "functionContext must not be null");
        this.analysisData = functionContext.analysisData();
        this.graph = functionContext.requireFrontendCfgGraph();
        this.function = functionContext.targetFunction();
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.valueMaterializations = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                graph,
                analysisData,
                this.classRegistry
        );
        this.cfgNodeProcessors = FrontendCfgNodeInsnLoweringProcessors.createRegistry();
        this.sequenceItemProcessors = FrontendSequenceItemInsnLoweringProcessors.createRegistry();
        this.opaqueExprProcessors = FrontendOpaqueExprInsnLoweringProcessors.createRegistry();
    }

    public void run() {
        requireShellOnlyTarget();
        declareSelfSlotIfNeeded();
        declareSourceLocalSlots();
        declareCfgValueSlots();
        createBlocks();
        lowerBlocks();
    }

    @NotNull LirBasicBlock lowerSequenceItem(@NotNull LirBasicBlock block, @NotNull SequenceItem item) {
        return sequenceItemProcessors.lower(this, block, item, null);
    }

    @NotNull LirBasicBlock lowerOpaqueExpression(@NotNull LirBasicBlock block, @NotNull OpaqueExprValueItem item) {
        return opaqueExprProcessors.lower(this, block, item.expression(), new OpaqueExprLoweringContext(item));
    }

    void lowerAssignmentTarget(@NotNull LirBasicBlock block, @NotNull AssignmentItem item, @NotNull String rhsSlotId) {
        FrontendAssignmentTargetInsnLoweringProcessors.lowerPublishedWritableRoute(this, block, item, rhsSlotId);
    }

    @NotNull FrontendBinding requireBinding(@NotNull Node useSite) {
        var binding = analysisData.symbolBindings().get(Objects.requireNonNull(useSite, "useSite must not be null"));
        if (binding == null) {
            throw new IllegalStateException("Missing published symbol binding for " + useSite.getClass().getSimpleName());
        }
        return binding;
    }

    /// `FrontendTopBindingAnalyzer` only publishes binding kind `SELF` for explicit
    /// `SelfExpression`. Any identifier node that still carries `SELF` means some earlier
    /// publication step leaked an impossible surface into lowering, so all body-lowering entry
    /// points must reject it consistently instead of silently rewriting the identifier to `self`.
    @NotNull IllegalStateException identifierSelfBindingContractViolation(
            @NotNull IdentifierExpression identifierExpression,
            @NotNull String contractDetail
    ) {
        Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        return new IllegalStateException(
                StringUtil.requireNonBlank(contractDetail, "contractDetail")
                        + " must use explicit SelfExpression instead of identifier binding kind SELF"
        );
    }

    /// Consumes one lowering-ready published call fact.
    ///
    /// Compile gate and CFG publication already accept both exact `RESOLVED` routes and runtime-open
    /// `DYNAMIC` routes. Body lowering therefore reads the same frozen contract here instead of
    /// silently narrowing the accepted surface back to resolved-only.
    @NotNull FrontendResolvedCall requireResolvedCall(@NotNull Node callAnchor) {
        var resolvedCall = analysisData.resolvedCalls().get(Objects.requireNonNull(callAnchor, "callAnchor must not be null"));
        if (resolvedCall == null) {
            throw new IllegalStateException(
                    "Missing published resolved call for " + callAnchor.getClass().getSimpleName()
            );
        }
        if (resolvedCall.status() != FrontendCallResolutionStatus.RESOLVED
                && resolvedCall.status() != FrontendCallResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Call anchor " + callAnchor.getClass().getSimpleName() + " is not lowering-ready: " + resolvedCall.status()
            );
        }
        return resolvedCall;
    }

    @NotNull FrontendResolvedMember requireResolvedMember(@NotNull Node memberAnchor) {
        var resolvedMember = analysisData.resolvedMembers().get(
                Objects.requireNonNull(memberAnchor, "memberAnchor must not be null")
        );
        if (resolvedMember == null) {
            throw new IllegalStateException(
                    "Missing published resolved member for " + memberAnchor.getClass().getSimpleName()
            );
        }
        if (resolvedMember.status() != FrontendMemberResolutionStatus.RESOLVED) {
            throw new IllegalStateException(
                    "Member anchor " + memberAnchor.getClass().getSimpleName()
                            + " is not lowering-ready: "
                            + resolvedMember.status()
            );
        }
        return resolvedMember;
    }

    void requireOpaqueOperandCount(@NotNull OpaqueExprValueItem item, int expectedCount) {
        if (item.operandValueIds().size() != expectedCount) {
            throw unsupportedSequenceItem(
                    item,
                    "expected " + expectedCount + " operand value ids, but got " + item.operandValueIds().size()
            );
        }
    }

    void requireSingleSubscriptArgument(@NotNull Node anchor, @NotNull List<?> arguments) {
        if (arguments.size() != 1) {
            throw new IllegalStateException(
                    "Subscript lowering currently supports exactly one key operand for "
                            + anchor.getClass().getSimpleName()
            );
        }
    }

    @NotNull String resolveInstanceCallReceiver(@NotNull CallItem item) {
        var receiverValueId = item.receiverValueIdOrNull();
        if (receiverValueId != null) {
            return slotIdForValue(receiverValueId);
        }
        requireSelfSlot();
        return "self";
    }

    /// Materializes the current call-receiver leaf through the shared writable-route support.
    ///
    /// When the CFG has already published one concrete receiver value id, that slot is the direct
    /// receiver leaf and must be reused as-is. The writable-route payload then exists only to carry
    /// post-call reverse-commit provenance. The old direct-slot repair logic now lives entirely in
    /// publication/materialization: if some payload-backed call needs a real source slot here, its
    /// dedicated `receiverValueIdOrNull` must already be an alias-backed value id.
    /// Body lowering therefore never re-reads a payload-backed leaf on demand.
    @NotNull String materializeCallReceiverLeaf(@NotNull LirBasicBlock block, @NotNull CallItem item) {
        Objects.requireNonNull(block, "block must not be null");
        var actualItem = Objects.requireNonNull(item, "item must not be null");
        if (actualItem.writableRoutePayloadOrNull() != null) {
            if (actualItem.receiverValueIdOrNull() != null) {
                return slotIdForValue(actualItem.receiverValueIdOrNull());
            }
            throw new IllegalStateException(
                    "Payload-backed call '"
                            + actualItem.callableName()
                            + "' must publish dedicated receiverValueIdOrNull before body lowering"
            );
        }
        if (actualItem.receiverValueIdOrNull() != null) {
            return slotIdForValue(actualItem.receiverValueIdOrNull());
        }
        var receiverSlotId = resolveInstanceCallReceiver(actualItem);
        var receiverType = requireFunctionVariableType(receiverSlotId);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                actualItem.anchor(),
                new FrontendWritableRouteSupport.FrontendWritableRoot("call receiver", receiverSlotId, receiverType),
                new FrontendWritableRouteSupport.DirectSlotLeaf(receiverSlotId, receiverType),
                List.of()
        );
        return FrontendWritableRouteSupport.materializeLeafRead(this, block, chain, "call_receiver");
    }

    @NotNull FrontendWritableRouteSupport.FrontendWritableAccessChain requireWritableAccessChain(
            @NotNull FrontendWritableRoutePayload payload
    ) {
        var actualPayload = Objects.requireNonNull(payload, "payload must not be null");
        return new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                actualPayload.routeAnchor(),
                materializeWritableRouteRoot(actualPayload.root()),
                materializeWritableLeaf(actualPayload),
                actualPayload.reverseCommitSteps().stream()
                        .map(step -> materializeWritableCommitStep(actualPayload.root(), step))
                        .toList()
        );
    }

    private @NotNull FrontendWritableRouteSupport.FrontendWritableRoot materializeWritableRouteRoot(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root
    ) {
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> {
                var slotId = resolveDirectWritableRootSlot(root.anchor());
                yield new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "direct slot route",
                        slotId,
                        requireFunctionVariableType(slotId)
                );
            }
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "implicit self route",
                        "self",
                        requireFunctionVariableType("self")
                );
            }
            case STATIC_CONTEXT -> new FrontendWritableRouteSupport.FrontendWritableRoot(
                    "static route",
                    null,
                    new GdObjectType(currentClassName())
            );
            case VALUE_ID -> {
                var valueId = Objects.requireNonNull(root.valueIdOrNull(), "VALUE_ID root must publish valueIdOrNull");
                yield new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "value route",
                        slotIdForValue(valueId),
                        requireValueType(valueId)
                );
            }
        };
    }

    private @NotNull FrontendWritableRouteSupport.FrontendWritableLeaf materializeWritableLeaf(
            @NotNull FrontendWritableRoutePayload payload
    ) {
        var root = payload.root();
        var leaf = payload.leaf();
        var leafType = requireWritableLeafType(payload);
        return switch (leaf.kind()) {
            case DIRECT_SLOT -> new FrontendWritableRouteSupport.DirectSlotLeaf(
                    resolveDirectWritableLeafSlot(root),
                    leafType
            );
            case PROPERTY -> {
                var propertyName = Objects.requireNonNull(leaf.memberNameOrNull(), "PROPERTY leaf must publish memberNameOrNull");
                if (isStaticWritablePropertyRoute(root, leaf.anchor())) {
                    yield new FrontendWritableRouteSupport.StaticPropertyLeaf(
                            requireStaticWritableReceiverName(root, leaf.anchor()),
                            propertyName,
                            leafType
                    );
                }
                yield new FrontendWritableRouteSupport.InstancePropertyLeaf(
                        resolveWritableContainerSlot(root, leaf.containerValueIdOrNull()),
                        propertyName,
                        leafType
                );
            }
            case SUBSCRIPT -> new FrontendWritableRouteSupport.SubscriptLeaf(
                    resolveWritableContainerSlot(root, leaf.containerValueIdOrNull()),
                    leaf.memberNameOrNull(),
                    slotIdForValue(leaf.operandValueIds().getFirst()),
                    Objects.requireNonNull(
                            leaf.subscriptAccessKindOrNull(),
                            "SUBSCRIPT leaf must publish subscriptAccessKindOrNull"
                    ),
                    leafType
            );
        };
    }

    private @NotNull FrontendWritableRouteSupport.FrontendWritableCommitStep materializeWritableCommitStep(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull FrontendWritableRoutePayload.StepDescriptor step
    ) {
        return switch (Objects.requireNonNull(step, "step must not be null").kind()) {
            case PROPERTY -> {
                var propertyName = Objects.requireNonNull(step.memberNameOrNull(), "PROPERTY step must publish memberNameOrNull");
                if (isStaticWritablePropertyRoute(root, step.anchor())) {
                    yield new FrontendWritableRouteSupport.StaticPropertyCommitStep(
                            requireStaticWritableReceiverName(root, step.anchor()),
                            propertyName
                    );
                }
                yield new FrontendWritableRouteSupport.InstancePropertyCommitStep(
                        resolveWritableContainerSlot(root, step.containerValueIdOrNull()),
                        propertyName
                );
            }
            case SUBSCRIPT -> new FrontendWritableRouteSupport.SubscriptCommitStep(
                    resolveWritableContainerSlot(root, step.containerValueIdOrNull()),
                    step.memberNameOrNull(),
                    slotIdForValue(step.operandValueIds().getFirst()),
                    Objects.requireNonNull(
                            step.subscriptAccessKindOrNull(),
                            "SUBSCRIPT step must publish subscriptAccessKindOrNull"
                    )
            );
        };
    }

    private @NotNull String resolveDirectWritableLeafSlot(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root
    ) {
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> resolveDirectWritableRootSlot(root.anchor());
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield "self";
            }
            case VALUE_ID ->
                    slotIdForValue(Objects.requireNonNull(root.valueIdOrNull(), "VALUE_ID root must publish valueIdOrNull"));
            case STATIC_CONTEXT ->
                    throw new IllegalStateException("STATIC_CONTEXT root cannot materialize a direct-slot leaf");
        };
    }

    private @NotNull String resolveWritableContainerSlot(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @Nullable String containerValueIdOrNull
    ) {
        if (containerValueIdOrNull != null) {
            return slotIdForValue(containerValueIdOrNull);
        }
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> resolveDirectWritableRootSlot(root.anchor());
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield "self";
            }
            case VALUE_ID ->
                    slotIdForValue(Objects.requireNonNull(root.valueIdOrNull(), "VALUE_ID root must publish valueIdOrNull"));
            case STATIC_CONTEXT -> throw new IllegalStateException(
                    "STATIC_CONTEXT root requires an explicit container value id for non-property writable routes"
            );
        };
    }

    private @NotNull String resolveDirectWritableRootSlot(@NotNull Node rootAnchor) {
        return switch (Objects.requireNonNull(rootAnchor, "rootAnchor must not be null")) {
            case SelfExpression _ -> {
                requireSelfSlot();
                yield "self";
            }
            case IdentifierExpression identifierExpression -> {
                var binding = requireBinding(identifierExpression);
                yield switch (binding.kind()) {
                    case LOCAL_VAR, PARAMETER, CAPTURE -> binding.symbolName();
                    case SELF -> throw identifierSelfBindingContractViolation(
                            identifierExpression,
                            "DIRECT_SLOT writable root"
                    );
                    default -> throw new IllegalStateException(
                            "DIRECT_SLOT writable root requires a storage-backed binding, but got " + binding.kind()
                    );
                };
            }
            default -> throw new IllegalStateException(
                    "DIRECT_SLOT writable root requires IdentifierExpression or SelfExpression, but got "
                            + rootAnchor.getClass().getSimpleName()
            );
        };
    }

    private boolean isStaticWritablePropertyRoute(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull Node propertyAnchor
    ) {
        return switch (Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")) {
            case IdentifierExpression _ -> {
                var binding = requireBinding(propertyAnchor);
                yield isStaticPropertyBinding(binding);
            }
            case dev.superice.gdparser.frontend.ast.AttributePropertyStep _ -> {
                var resolvedMember = requireResolvedMember(propertyAnchor);
                yield resolvedMember.receiverKind() == dev.superice.gdcc.frontend.sema.FrontendReceiverKind.TYPE_META;
            }
            default -> root.kind() == FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT
                    && root.anchor() == propertyAnchor;
        };
    }

    private @NotNull String requireStaticWritableReceiverName(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull Node propertyAnchor
    ) {
        if (root.kind() == FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT && root.anchor() == propertyAnchor) {
            return currentClassName();
        }
        return switch (Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")) {
            case dev.superice.gdparser.frontend.ast.AttributePropertyStep _ -> requireStaticReceiverName(
                    requireResolvedMember(propertyAnchor).receiverType()
            );
            case IdentifierExpression _ -> currentClassName();
            default -> throw new IllegalStateException(
                    "Static writable property route requires a type-meta or static-binding anchor"
            );
        };
    }

    private @NotNull GdType requireWritableLeafType(@NotNull FrontendWritableRoutePayload payload) {
        var published = analysisData.expressionTypes().get(payload.leaf().anchor());
        if (published != null && published.publishedType() != null) {
            return published.publishedType();
        }
        return switch (payload.leaf().kind()) {
            case DIRECT_SLOT -> requireWritableDirectLeafType(payload.root());
            case PROPERTY -> requireWritablePropertyLeafType(payload.leaf().anchor());
            case SUBSCRIPT -> requireWritableSubscriptLeafType(payload.root(), payload.leaf());
        };
    }

    private @NotNull GdType requireWritableDirectLeafType(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root
    ) {
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> requireFunctionVariableType(resolveDirectWritableRootSlot(root.anchor()));
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield requireFunctionVariableType("self");
            }
            case VALUE_ID -> requireValueType(Objects.requireNonNull(
                    root.valueIdOrNull(),
                    "VALUE_ID root must publish valueIdOrNull"
            ));
            case STATIC_CONTEXT -> throw new IllegalStateException(
                    "STATIC_CONTEXT root cannot materialize a direct-slot leaf type"
            );
        };
    }

    private @NotNull GdType requireWritablePropertyLeafType(@NotNull Node propertyAnchor) {
        return switch (Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")) {
            case IdentifierExpression _ -> requireWritableBindingStorageType(requireBinding(propertyAnchor));
            case dev.superice.gdparser.frontend.ast.AttributePropertyStep _ -> Objects.requireNonNull(
                    requireResolvedMember(propertyAnchor).resultType(),
                    "Resolved property leaf must publish resultType"
            );
            default -> throw new IllegalStateException(
                    "Writable property leaf type requires IdentifierExpression or AttributePropertyStep anchor"
            );
        };
    }

    private @NotNull GdType requireWritableSubscriptLeafType(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull FrontendWritableRoutePayload.LeafDescriptor leaf
    ) {
        var containerType = requireWritableContainerType(root, leaf.containerValueIdOrNull());
        return switch (containerType) {
            case GdVariantType _ -> GdVariantType.VARIANT;
            case GdContainerType container -> container.getValueType();
            default -> throw new IllegalStateException(
                    "Writable subscript leaf requires container-family or Variant carrier, but got "
                            + containerType.getTypeName()
            );
        };
    }

    private @NotNull GdType requireWritableContainerType(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @Nullable String containerValueIdOrNull
    ) {
        if (containerValueIdOrNull != null) {
            return requireValueType(containerValueIdOrNull);
        }
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> requireFunctionVariableType(resolveDirectWritableRootSlot(root.anchor()));
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield requireFunctionVariableType("self");
            }
            case STATIC_CONTEXT -> throw new IllegalStateException(
                    "STATIC_CONTEXT root requires an explicit container value id for non-property writable routes"
            );
            case VALUE_ID -> requireValueType(Objects.requireNonNull(
                    root.valueIdOrNull(),
                    "VALUE_ID root must publish valueIdOrNull"
            ));
        };
    }

    private @NotNull GdType requireWritableBindingStorageType(@NotNull FrontendBinding binding) {
        return switch (Objects.requireNonNull(binding, "binding must not be null").kind()) {
            case LOCAL_VAR, PARAMETER, CAPTURE -> requireFunctionVariableType(binding.symbolName());
            case SELF -> throw new IllegalStateException(
                    "Writable binding storage lookup must use explicit SelfExpression instead of binding kind SELF"
            );
            case PROPERTY -> switch (Objects.requireNonNull(
                    binding.declarationSite(),
                    "Property binding must carry declaration metadata"
            )) {
                case PropertyDef propertyDef -> propertyDef.getType();
                default -> throw new IllegalStateException(
                        "Property binding '" + binding.symbolName() + "' does not carry property metadata"
                );
            };
            default -> throw new IllegalStateException(
                    "Binding '" + binding.symbolName() + "' is not backed by writable storage"
            );
        };
    }

    void requireSelfSlot() {
        if (function.getVariableById("self") == null) {
            throw new IllegalStateException(
                    describeContext() + " requires an implicit self receiver slot"
            );
        }
    }

    void ensureVariable(@NotNull String variableId, @NotNull GdType expectedType) {
        var existing = function.getVariableById(variableId);
        if (existing == null) {
            function.createAndAddVariable(variableId, expectedType);
            return;
        }
        if (!existing.type().equals(expectedType)) {
            throw new IllegalStateException(
                    "Variable '" + variableId + "' already exists with type "
                            + existing.type().getTypeName()
                            + ", expected "
                            + expectedType.getTypeName()
            );
        }
    }

    @NotNull LirBasicBlock requireBlock(@NotNull String blockId) {
        var block = function.getBasicBlock(blockId);
        if (block == null) {
            throw new IllegalStateException("LIR basic block has not been materialized: " + blockId);
        }
        return block;
    }

    @NotNull GdType requireValueType(@NotNull String valueId) {
        var materialization = requireValueMaterialization(valueId);
        return materialization.type();
    }

    @NotNull String slotIdForValue(@NotNull String valueId) {
        var materialization = requireValueMaterialization(valueId);
        return switch (materialization.kind()) {
            case TEMP_SLOT -> FrontendBodyLoweringSupport.cfgTempSlotId(valueId);
            case MERGE_SLOT -> FrontendBodyLoweringSupport.mergeSlotId(valueId);
            case SOURCE_SLOT_ALIAS -> resolveDirectWritableRootSlot(Objects.requireNonNull(
                    materialization.aliasSourceAnchorOrNull(),
                    "SOURCE_SLOT_ALIAS materialization must carry aliasSourceAnchorOrNull"
            ));
        };
    }

    @NotNull String resultSlotId(@NotNull OpaqueExprValueItem item) {
        return FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId());
    }

    @NotNull GdType requireSourceLocalSlotType(@NotNull VariableDeclaration declaration) {
        return FrontendBodyLoweringSupport.requireSourceLocalSlotType(analysisData, declaration);
    }

    @NotNull GdType requireFunctionVariableType(@NotNull String variableId) {
        var variable = function.getVariableById(StringUtil.requireNonBlank(variableId, "variableId"));
        if (variable == null) {
            throw new IllegalStateException("Missing lowered function variable '" + variableId + "'");
        }
        return variable.type();
    }

    @NotNull LirFunctionDef targetFunction() {
        return function;
    }

    /// Materializes one already-approved ordinary frontend typed boundary into an explicit LIR slot.
    ///
    /// The authoritative boundary matrix lives in
    /// `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`; semantic legality is owned
    /// by `FrontendVariantBoundaryCompatibility`, and this helper must stay isomorphic to that
    /// decision table instead of inventing extra lowering-only conversions. The long-form consumer and
    /// materialization contract lives in
    /// `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`.
    ///
    /// Later assignment/call/return processors should route all ordinary boundary writes through
    /// this helper instead of duplicating ad-hoc boundary branches.
    @NotNull String materializeFrontendBoundaryValue(
            @NotNull LirBasicBlock block,
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull String boundaryUse
    ) {
        Objects.requireNonNull(block, "block must not be null");
        var sourceSlot = StringUtil.requireNonBlank(sourceSlotId, "sourceSlotId");
        var source = Objects.requireNonNull(sourceType, "sourceType must not be null");
        var target = Objects.requireNonNull(targetType, "targetType must not be null");
        var use = StringUtil.requireNonBlank(boundaryUse, "boundaryUse");
        if (source instanceof GdVoidType) {
            throw new IllegalStateException(
                    "Frontend boundary '"
                            + use
                            + "' must not materialize source type void; statement-position RESOLVED(void) calls must omit result slots, and value-required void calls should have failed before body lowering"
            );
        }
        if (target instanceof GdVoidType) {
            throw new IllegalStateException(
                    "Frontend boundary '"
                            + use
                            + "' must not materialize into target type void; value-required lowering sites must not request a concrete slot for void"
            );
        }
        if (target instanceof GdVariantType) {
            if (source instanceof GdVariantType) {
                return sourceSlot;
            }
            var packedSlotId = nextBoundaryMaterializationSlotId(use, "pack");
            ensureVariable(packedSlotId, GdVariantType.VARIANT);
            block.appendNonTerminatorInstruction(new PackVariantInsn(packedSlotId, sourceSlot));
            return packedSlotId;
        }
        if (source instanceof GdVariantType) {
            var unpackedSlotId = nextBoundaryMaterializationSlotId(use, "unpack");
            ensureVariable(unpackedSlotId, target);
            block.appendNonTerminatorInstruction(new UnpackVariantInsn(unpackedSlotId, sourceSlot));
            return unpackedSlotId;
        }
        if (source instanceof GdNilType && target instanceof GdObjectType) {
            var nullSlotId = nextBoundaryMaterializationSlotId(use, "null_object");
            ensureVariable(nullSlotId, target);
            block.appendNonTerminatorInstruction(new LiteralNullInsn(nullSlotId));
            return nullSlotId;
        }
        return sourceSlot;
    }

    /// Materializes call operands against the already-published route contract.
    ///
    /// Exact member-call routes prefer the already-published normalized callable boundary so lowering
    /// does not rebuild parameter types from raw metadata. Legacy routes that still do not publish an
    /// exact boundary, such as bare-call fallback and constructor-specific paths, keep using the old
    /// callable-signature helper until they are migrated. `DYNAMIC` calls intentionally bypass any
    /// exact signature lookup and forward their already-evaluated operand slots unchanged.
    @NotNull List<LirInstruction.Operand> materializeCallArguments(
            @NotNull LirBasicBlock block,
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        Objects.requireNonNull(block, "block must not be null");
        Objects.requireNonNull(item, "item must not be null");
        var argumentValueIds = item.argumentValueIds();
        if (argumentValueIds.isEmpty()) {
            return List.of();
        }
        if (resolvedCall.status() == FrontendCallResolutionStatus.DYNAMIC) {
            return argumentValueIds.stream()
                    .map(this::slotIdForValue)
                    .<LirInstruction.Operand>map(LirInstruction.VariableOperand::new)
                    .toList();
        }
        var boundaryPlan = requireCallArgumentBoundaryPlan(item, resolvedCall);
        if (!boundaryPlan.isVararg() && argumentValueIds.size() > boundaryPlan.fixedParameterTypes().size()) {
            throw new IllegalStateException(
                    "Resolved call '" + resolvedCall.callableName() + "' provides "
                            + argumentValueIds.size()
                            + " arguments for a non-vararg signature with "
                            + boundaryPlan.fixedParameterTypes().size()
                            + " fixed parameters"
            );
        }

        var operands = new ArrayList<LirInstruction.Operand>(argumentValueIds.size());
        var fixedPrefixCount = Math.min(argumentValueIds.size(), boundaryPlan.fixedParameterTypes().size());
        for (var index = 0; index < fixedPrefixCount; index++) {
            var argumentValueId = argumentValueIds.get(index);
            var materializedSlotId = materializeFrontendBoundaryValue(
                    block,
                    slotIdForValue(argumentValueId),
                    requireValueType(argumentValueId),
                    boundaryPlan.fixedParameterTypes().get(index),
                    "call_fixed_" + index
            );
            operands.add(new LirInstruction.VariableOperand(materializedSlotId));
        }
        for (var index = fixedPrefixCount; index < argumentValueIds.size(); index++) {
            var argumentValueId = argumentValueIds.get(index);
            var materializedSlotId = materializeFrontendBoundaryValue(
                    block,
                    slotIdForValue(argumentValueId),
                    requireValueType(argumentValueId),
                    GdVariantType.VARIANT,
                    "call_vararg_" + index
            );
            operands.add(new LirInstruction.VariableOperand(materializedSlotId));
        }
        return List.copyOf(operands);
    }

    boolean isStaticPropertyBinding(@NotNull FrontendBinding binding) {
        return binding.declarationSite() instanceof VariableDeclaration variableDeclaration
                && variableDeclaration.isStatic();
    }

    @NotNull String currentClassName() {
        return functionContext.owningClass().getName();
    }

    @NotNull String requireClassName(@Nullable GdType receiverType) {
        return switch (Objects.requireNonNull(receiverType, "receiverType must not be null")) {
            case GdObjectType(var className) -> className;
            default -> throw new IllegalStateException(
                    "Static receiver type must be an object/class type, but was " + receiverType.getTypeName()
            );
        };
    }

    @NotNull String requireStaticReceiverName(@Nullable GdType receiverType) {
        Objects.requireNonNull(receiverType, "receiverType must not be null");
        if (receiverType instanceof GdObjectType(var className)) {
            return className;
        }
        var builtinTypeName = receiverType.getTypeName();
        if (classRegistry.findBuiltinClass(builtinTypeName) != null) {
            return builtinTypeName;
        }
        throw new IllegalStateException(
                "Static receiver type must be an engine/script class or builtin type, but was "
                        + receiverType.getTypeName()
        );
    }

    /// Allocates one body-local helper temp owned by writable-route lowering.
    ///
    /// Ordinary CFG value slots continue to use `cfgTempSlotId(...)` / `mergeSlotId(...)`. This
    /// helper is reserved for support-local scratch values such as named-member intermediates and
    /// leaf-read temps so those scratch slots stay clearly separated from published CFG value ids.
    @NotNull String allocateWritableRouteTemp(
            @NotNull String purpose,
            @NotNull GdType type
    ) {
        var slotId = "cfg_writable_"
                + StringUtil.requireNonBlank(purpose, "purpose")
                + "_"
                + writableRouteMaterializationCounter++;
        ensureVariable(slotId, Objects.requireNonNull(type, "type must not be null"));
        return slotId;
    }

    /// Allocates one synthetic basic block owned by writable-route lowering.
    ///
    /// The writable-route runtime gate path may need to split the current lexical block into
    /// `apply` / `skip` / `continue` regions. Those blocks are not frontend CFG nodes; they are
    /// body-lowering artifacts that must still live in the target LIR function so later validation
    /// and codegen can see the real branch structure.
    @NotNull LirBasicBlock createWritableRouteBlock(@NotNull String purpose) {
        var blockId = "cfg_writable_bb_"
                + StringUtil.requireNonBlank(purpose, "purpose")
                + "_"
                + writableRouteBlockCounter++;
        var block = new LirBasicBlock(blockId);
        function.addBasicBlock(block);
        return block;
    }

    @NotNull IllegalStateException unsupportedSequenceItem(@NotNull SequenceItem item, @NotNull String detail) {
        return new IllegalStateException(
                item.getClass().getSimpleName()
                        + " is not supported by frontend body lowering yet: "
                        + detail
        );
    }

    private void requireShellOnlyTarget() {
        if (function.getBasicBlockCount() != 0 || !function.getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(describeContext() + " must remain shell-only before body lowering");
        }
    }

    private @NotNull String describeContext() {
        return "Function lowering context "
                + functionContext.kind()
                + " "
                + functionContext.owningClass().getName()
                + "."
                + function.getName();
    }

    private void declareSelfSlotIfNeeded() {
        if (function.isStatic()) {
            return;
        }
        ensureVariable("self", new GdObjectType(currentClassName()));
    }

    private void declareSourceLocalSlots() {
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof LocalDeclarationItem localDeclarationItem)) {
                    continue;
                }
                var declaration = localDeclarationItem.declaration();
                var slotType = FrontendBodyLoweringSupport.requireSourceLocalSlotType(analysisData, declaration);
                ensureVariable(FrontendBodyLoweringSupport.sourceLocalSlotId(declaration), slotType);
            }
        }
    }

    private void declareCfgValueSlots() {
        for (var entry : valueMaterializations.entrySet()) {
            var valueId = entry.getKey();
            var materialization = entry.getValue();
            switch (materialization.kind()) {
                case TEMP_SLOT ->
                        ensureVariable(FrontendBodyLoweringSupport.cfgTempSlotId(valueId), materialization.type());
                case MERGE_SLOT ->
                        ensureVariable(FrontendBodyLoweringSupport.mergeSlotId(valueId), materialization.type());
                case SOURCE_SLOT_ALIAS -> {
                    // Alias-backed values intentionally reuse an existing trusted source slot instead of
                    // declaring a second `cfg_tmp_*` variable that call lowering would never truly consume.
                }
            }
        }
    }

    private void createBlocks() {
        for (var nodeId : graph.nodeIds()) {
            if (graph.requireNode(nodeId) instanceof FrontendCfgGraph.StopNode stopNode
                    && stopNode.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE) {
                continue;
            }
            function.addBasicBlock(new LirBasicBlock(nodeId));
        }
        function.setEntryBlockId(graph.entryNodeId());
    }

    private void lowerBlocks() {
        for (var nodeId : graph.nodeIds()) {
            if (graph.requireNode(nodeId) instanceof FrontendCfgGraph.StopNode stopNode
                    && stopNode.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE) {
                continue;
            }
            var block = requireBlock(nodeId);
            cfgNodeProcessors.lower(this, block, graph.requireNode(nodeId), null);
        }
    }

    private @NotNull String nextBoundaryMaterializationSlotId(
            @NotNull String boundaryUse,
            @NotNull String operation
    ) {
        return "cfg_boundary_"
                + StringUtil.requireNonBlank(boundaryUse, "boundaryUse")
                + "_"
                + StringUtil.requireNonBlank(operation, "operation")
                + "_"
                + boundaryMaterializationCounter++;
    }

    /// Legacy fallback for routes that still lower through raw callable metadata.
    ///
    /// Constructor routes such as `Node.new()` or unary builtin-from-`Variant` special cases may
    /// intentionally publish owner metadata instead of a synthetic callable. Bare-call exact routes
    /// also have not migrated to the Phase B exact-boundary publication yet, so they still rely on
    /// this helper until their own publication contract is tightened.
    private @NotNull FunctionDef requireBoundaryCallableSignature(@NotNull FrontendResolvedCall resolvedCall) {
        var declarationSite = Objects.requireNonNull(
                Objects.requireNonNull(resolvedCall, "resolvedCall must not be null").declarationSite(),
                "Resolved call must carry declaration metadata"
        );
        return switch (declarationSite) {
            case FunctionDef functionDef -> functionDef;
            default -> throw new IllegalStateException(
                    "Resolved call '" + resolvedCall.callableName()
                            + "' does not carry callable signature metadata required for argument materialization"
            );
        };
    }

    private @NotNull CallArgumentBoundaryPlan requireCallArgumentBoundaryPlan(
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        var publishedBoundary = resolvedCall.exactCallableBoundary();
        if (publishedBoundary != null) {
            return new CallArgumentBoundaryPlan(
                    publishedBoundary.fixedParameterTypes(),
                    publishedBoundary.isVararg()
            );
        }
        if (requiresPublishedExactCallableBoundary(item, resolvedCall)) {
            throw new IllegalStateException(
                    "Exact call '" + resolvedCall.callableName()
                            + "' is missing published callable boundary metadata required for argument materialization"
            );
        }
        var callable = requireBoundaryCallableSignature(resolvedCall);
        return new CallArgumentBoundaryPlan(
                callBoundaryParameterTypes(callable, resolvedCall.callKind()),
                callable.isVararg()
        );
    }

    /// Exact instance-call CFG items already publish a concrete receiver value and therefore should
    /// also carry the shared resolver's fixed-parameter boundary. Losing that boundary is a frontend
    /// invariant violation, not a reason to fall back to raw `FunctionDef` metadata.
    private boolean requiresPublishedExactCallableBoundary(
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        return resolvedCall.status() == FrontendCallResolutionStatus.RESOLVED
                && resolvedCall.callKind() == FrontendCallResolutionKind.INSTANCE_METHOD
                && item.receiverValueIdOrNull() != null;
    }

    /// Call instructions materialize the receiver separately, so any legacy frontend-facing
    /// signature metadata that still exposes an implicit `self` parameter must be normalized away.
    private @NotNull List<GdType> callBoundaryParameterTypes(
            @NotNull FunctionDef callable,
            @NotNull FrontendCallResolutionKind callKind
    ) {
        var parameters = List.copyOf(callable.getParameters());
        if (callKind == FrontendCallResolutionKind.INSTANCE_METHOD
                && !callable.isStatic()
                && !parameters.isEmpty()
                && parameters.getFirst().getName().equals("self")) {
            return parameters.stream()
                    .skip(1)
                    .map(ParameterDef::getType)
                    .toList();
        }
        return parameters.stream()
                .map(ParameterDef::getType)
                .toList();
    }

    private record CallArgumentBoundaryPlan(
            @NotNull List<GdType> fixedParameterTypes,
            boolean isVararg
    ) {
        private CallArgumentBoundaryPlan {
            fixedParameterTypes = List.copyOf(Objects.requireNonNull(
                    fixedParameterTypes,
                    "fixedParameterTypes must not be null"
            ));
        }
    }

    private @NotNull FrontendBodyLoweringSupport.CfgValueMaterialization requireValueMaterialization(@NotNull String valueId) {
        var materialization = valueMaterializations.get(Objects.requireNonNull(valueId, "valueId must not be null"));
        if (materialization == null) {
            throw new IllegalStateException("Missing published materialization for frontend CFG value id '" + valueId + "'");
        }
        return materialization;
    }

    record OpaqueExprLoweringContext(@NotNull OpaqueExprValueItem item) {
        OpaqueExprLoweringContext {
            Objects.requireNonNull(item, "item must not be null");
        }
    }

}
