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
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
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

/// Stateful carrier for one function-body lowering run.
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
    private final @NotNull ClassRegistry classRegistry;
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
    private int writableRouteMaterializationCounter;

    public FrontendBodyLoweringSession(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull ClassRegistry classRegistry
    ) {
        this.functionContext = Objects.requireNonNull(functionContext, "functionContext must not be null");
        this.analysisData = functionContext.analysisData();
        this.graph = functionContext.requireFrontendCfgGraph();
        this.function = functionContext.targetFunction();
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.valueTypes = FrontendBodyLoweringSupport.collectCfgValueSlotTypes(
                graph,
                analysisData,
                this.classRegistry
        );
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
    /// Current `CallItem` publication still exposes only the direct-slot subset because it does not
    /// yet carry a frozen writable-route payload. Routing even the trivial receiver path through the
    /// shared support keeps call lowering on the same leaf-selection entry point as
    /// assignment/compound-assignment routes and avoids a second receiver-specific helper stack.
    @NotNull String materializeCallReceiverLeaf(@NotNull LirBasicBlock block, @NotNull CallItem item) {
        Objects.requireNonNull(block, "block must not be null");
        var receiverSlotId = resolveInstanceCallReceiver(Objects.requireNonNull(item, "item must not be null"));
        var receiverType = item.receiverValueIdOrNull() == null
                ? requireFunctionVariableType(receiverSlotId)
                : requireValueType(item.receiverValueIdOrNull());
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                item.anchor(),
                new FrontendWritableRouteSupport.FrontendWritableRoot("call receiver", receiverSlotId, receiverType),
                new FrontendWritableRouteSupport.DirectSlotLeaf(receiverSlotId, receiverType),
                List.of()
        );
        return FrontendWritableRouteSupport.materializeLeafRead(this, block, chain, "call_receiver");
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
    /// Exact `RESOLVED` calls still consume their final callable signature here so fixed parameters
    /// can materialize the minimal ordinary `Variant` boundaries and vararg tails can be packed.
    /// `DYNAMIC` calls intentionally bypass signature lookup and forward their already-evaluated
    /// operand slots unchanged. Runtime dispatch stays on the backend route, while any later typed
    /// consumer of the published `Variant` result still goes through the ordinary boundary helper.
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
        var callable = requireBoundaryCallableSignature(resolvedCall);
        var parameterTypes = callBoundaryParameterTypes(callable, resolvedCall.callKind());
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

    /// Constructor routes such as `Node.new()` may intentionally publish class metadata instead of a
    /// synthetic zero-arg callable. We therefore demand a callable signature only when some actual
    /// argument values still need fixed-parameter boundary materialization.
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
