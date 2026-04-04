package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
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
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/// Stateful carrier for one executable-body lowering run.
///
/// The session owns the shared lowering facts for a single function:
/// - published semantic tables already accepted by compile gate
/// - declared temp/local/merge slots
/// - block materialization order
/// - processor registries for CFG nodes, sequence items, opaque expressions, and assignment targets
///
/// Processors may query this session for already-frozen information, but they must not rebuild
/// semantic facts or invent alternate evaluation order.
public final class FrontendBodyLoweringSession {
    private final @NotNull FunctionLoweringContext functionContext;
    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull FrontendCfgGraph graph;
    private final @NotNull LirFunctionDef function;
    private final @NotNull SequencedMap<String, GdType> valueTypes;
    private final @NotNull Set<String> mergeValueIds;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<FrontendCfgGraph.NodeDef, Void> cfgNodeProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<SequenceItem, Void> sequenceItemProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<Expression, OpaqueExprLoweringContext> opaqueExprProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<Expression, AssignmentTargetLoweringContext>
            assignmentTargetProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<Node, AssignmentTargetLoweringContext>
            attributeStepProcessors;
    private int boundaryMaterializationCounter;

    public FrontendBodyLoweringSession(@NotNull FunctionLoweringContext functionContext) {
        this.functionContext = Objects.requireNonNull(functionContext, "functionContext must not be null");
        this.analysisData = functionContext.analysisData();
        this.graph = functionContext.requireFrontendCfgGraph();
        this.function = functionContext.targetFunction();
        this.valueTypes = FrontendBodyLoweringSupport.collectCfgValueSlotTypes(graph, analysisData);
        this.mergeValueIds = collectMergeValueIds(graph);
        this.cfgNodeProcessors = FrontendCfgNodeInsnLoweringProcessors.createRegistry();
        this.sequenceItemProcessors = FrontendSequenceItemInsnLoweringProcessors.createRegistry();
        this.opaqueExprProcessors = FrontendOpaqueExprInsnLoweringProcessors.createRegistry();
        this.assignmentTargetProcessors = FrontendAssignmentTargetInsnLoweringProcessors.createTargetRegistry();
        this.attributeStepProcessors = FrontendAssignmentTargetInsnLoweringProcessors.createAttributeStepRegistry();
    }

    public void run() {
        requireShellOnlyTarget();
        declareSelfSlotIfNeeded();
        declareSourceLocalSlots();
        declareCfgValueSlots();
        createBlocks();
        lowerBlocks();
    }

    void lowerSequenceItem(@NotNull LirBasicBlock block, @NotNull SequenceItem item) {
        sequenceItemProcessors.lower(this, block, item, null);
    }

    void lowerOpaqueExpression(@NotNull LirBasicBlock block, @NotNull OpaqueExprValueItem item) {
        opaqueExprProcessors.lower(this, block, item.expression(), new OpaqueExprLoweringContext(item));
    }

    void lowerAssignmentTarget(@NotNull LirBasicBlock block, @NotNull AssignmentItem item, @NotNull String rhsSlotId) {
        assignmentTargetProcessors.lower(
                this,
                block,
                item.assignment().left(),
                new AssignmentTargetLoweringContext(item, rhsSlotId)
        );
    }

    void lowerAttributeAssignmentStep(
            @NotNull LirBasicBlock block,
            @NotNull Node attributeStep,
            @NotNull AssignmentTargetLoweringContext context
    ) {
        attributeStepProcessors.lower(this, block, attributeStep, context);
    }

    @NotNull FrontendBinding requireBinding(@NotNull Node useSite) {
        var binding = analysisData.symbolBindings().get(Objects.requireNonNull(useSite, "useSite must not be null"));
        if (binding == null) {
            throw new IllegalStateException("Missing published symbol binding for " + useSite.getClass().getSimpleName());
        }
        return binding;
    }

    @NotNull FrontendResolvedCall requireResolvedCall(@NotNull Node callAnchor) {
        var resolvedCall = analysisData.resolvedCalls().get(Objects.requireNonNull(callAnchor, "callAnchor must not be null"));
        if (resolvedCall == null) {
            throw new IllegalStateException(
                    "Missing published resolved call for " + callAnchor.getClass().getSimpleName()
            );
        }
        if (resolvedCall.status() != FrontendCallResolutionStatus.RESOLVED) {
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
        var valueType = valueTypes.get(Objects.requireNonNull(valueId, "valueId must not be null"));
        if (valueType == null) {
            throw new IllegalStateException("Missing published type for frontend CFG value id '" + valueId + "'");
        }
        return valueType;
    }

    @NotNull String slotIdForValue(@NotNull String valueId) {
        return mergeValueIds.contains(valueId)
                ? FrontendBodyLoweringSupport.mergeSlotId(valueId)
                : FrontendBodyLoweringSupport.cfgTempSlotId(valueId);
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

    /// Resolves the concrete storage type for one already-published bare assignment binding.
    ///
    /// Local/parameter/capture targets reuse the lowered function variable table, while property
    /// targets must carry shared property metadata so stores can materialize `Variant` boundaries
    /// without reopening resolver logic in the body pass.
    @NotNull GdType requireBindingAssignmentTargetType(@NotNull FrontendBinding binding) {
        return switch (Objects.requireNonNull(binding, "binding must not be null").kind()) {
            case LOCAL_VAR, PARAMETER, CAPTURE -> requireFunctionVariableType(binding.symbolName());
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
                    "Binding '" + binding.symbolName() + "' is not an assignment-backed storage location"
            );
        };
    }

    @NotNull LirFunctionDef targetFunction() {
        return function;
    }

    /// Materializes one already-approved ordinary frontend typed boundary into an explicit LIR slot.
    ///
    /// This helper deliberately stays narrower than condition normalization:
    /// - concrete -> `Variant` inserts `PackVariantInsn`
    /// - stable `Variant` -> concrete inserts `UnpackVariantInsn`
    /// - `Nil` -> object inserts an object-typed `LiteralNullInsn`
    /// - all remaining pairs stay direct because semantic analysis already decided legality
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

    /// Materializes fixed arguments against their declared parameter slots and packs any vararg
    /// tail values into `Variant`.
    ///
    /// Resolved-call facts already froze overload selection, so this helper only consumes the final
    /// callable signature and emits the minimal boundary `(un)pack` instructions needed for the
    /// selected route. It never retries overload resolution or re-derives parameter metadata.
    @NotNull List<LirInstruction.Operand> materializeCallArguments(
            @NotNull LirBasicBlock block,
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        Objects.requireNonNull(block, "block must not be null");
        Objects.requireNonNull(item, "item must not be null");
        var callable = requireResolvedCallableSignature(resolvedCall);
        var parameterTypes = callBoundaryParameterTypes(callable, resolvedCall.callKind());
        var argumentValueIds = item.argumentValueIds();
        if (!callable.isVararg() && argumentValueIds.size() > parameterTypes.size()) {
            throw new IllegalStateException(
                    "Resolved call '" + resolvedCall.callableName() + "' provides "
                            + argumentValueIds.size()
                            + " arguments for a non-vararg signature with "
                            + parameterTypes.size()
                            + " fixed parameters"
            );
        }

        var operands = new ArrayList<LirInstruction.Operand>(argumentValueIds.size());
        var fixedPrefixCount = Math.min(argumentValueIds.size(), parameterTypes.size());
        for (var index = 0; index < fixedPrefixCount; index++) {
            var argumentValueId = argumentValueIds.get(index);
            var materializedSlotId = materializeFrontendBoundaryValue(
                    block,
                    slotIdForValue(argumentValueId),
                    requireValueType(argumentValueId),
                    parameterTypes.get(index),
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

    @NotNull String namedBaseSlotId(@NotNull String valueId) {
        var slotId = FrontendBodyLoweringSupport.cfgTempSlotId(valueId) + "_named_base";
        ensureVariable(slotId, GdVariantType.VARIANT);
        return slotId;
    }

    @NotNull String namedKeySlotId(@NotNull String valueId) {
        var slotId = FrontendBodyLoweringSupport.cfgTempSlotId(valueId) + "_named_key";
        ensureVariable(slotId, GdStringNameType.STRING_NAME);
        return slotId;
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
        for (var entry : valueTypes.entrySet()) {
            var valueId = entry.getKey();
            var slotId = mergeValueIds.contains(valueId)
                    ? FrontendBodyLoweringSupport.mergeSlotId(valueId)
                    : FrontendBodyLoweringSupport.cfgTempSlotId(valueId);
            ensureVariable(slotId, entry.getValue());
        }
    }

    private void createBlocks() {
        for (var nodeId : graph.nodeIds()) {
            function.addBasicBlock(new LirBasicBlock(nodeId));
        }
        function.setEntryBlockId(graph.entryNodeId());
    }

    private void lowerBlocks() {
        for (var nodeId : graph.nodeIds()) {
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

    private @NotNull FunctionDef requireResolvedCallableSignature(@NotNull FrontendResolvedCall resolvedCall) {
        var declarationSite = Objects.requireNonNull(
                Objects.requireNonNull(resolvedCall, "resolvedCall must not be null").declarationSite(),
                "Resolved call must carry declaration metadata"
        );
        return switch (declarationSite) {
            case FunctionDef functionDef -> functionDef;
            default -> throw new IllegalStateException(
                    "Resolved call '" + resolvedCall.callableName() + "' does not carry callable signature metadata"
            );
        };
    }

    /// Call instructions materialize the receiver separately, so any frontend-facing signature
    /// metadata that still exposes an implicit `self` parameter must be normalized away here.
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

    private static @NotNull Set<String> collectMergeValueIds(@NotNull FrontendCfgGraph graph) {
        var mergeValueIds = new LinkedHashSet<String>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof MergeValueItem mergeValueItem) {
                    mergeValueIds.add(mergeValueItem.resultValueId());
                }
            }
        }
        return Set.copyOf(mergeValueIds);
    }

    record OpaqueExprLoweringContext(@NotNull OpaqueExprValueItem item) {
        OpaqueExprLoweringContext {
            Objects.requireNonNull(item, "item must not be null");
        }
    }

    record AssignmentTargetLoweringContext(
            @NotNull AssignmentItem item,
            @NotNull String rhsSlotId
    ) {
        AssignmentTargetLoweringContext {
            Objects.requireNonNull(item, "item must not be null");
            rhsSlotId = Objects.requireNonNull(rhsSlotId, "rhsSlotId must not be null");
        }
    }
}
