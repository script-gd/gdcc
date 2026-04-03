package dev.superice.gdcc.frontend.lowering.pass;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringContext;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPass;
import dev.superice.gdcc.frontend.lowering.FunctionLoweringContext;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CastItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.CallStaticMethodInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LineNumberInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralNilInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.lir.insn.StoreStaticInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantGetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantGetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/// Frontend CFG -> LIR body materialization pass.
///
/// The pass consumes only the frontend CFG graph plus already-published semantic facts. It must not
/// re-run chain reduction, overload selection, or child-evaluation planning.
public final class FrontendLoweringBodyInsnPass implements FrontendLoweringPass {
    @Override
    public void run(@NotNull FrontendLoweringContext context) {
        var analysisData = context.requireAnalysisData();
        for (var functionContext : context.requireFunctionLoweringContexts()) {
            if (functionContext.analysisData() != analysisData) {
                throw new IllegalStateException("Function lowering context must reuse the published analysis snapshot");
            }
            switch (functionContext.kind()) {
                case EXECUTABLE_BODY -> new BodyLoweringSession(functionContext).run();
                case PROPERTY_INIT -> requireShellOnlyTarget(functionContext);
                case PARAMETER_DEFAULT_INIT -> throw new IllegalStateException(
                        "Frontend body lowering pass does not support parameter default initializer contexts yet"
                );
            }
        }
    }

    private static void requireShellOnlyTarget(@NotNull FunctionLoweringContext functionContext) {
        if (functionContext.targetFunction().getBasicBlockCount() != 0
                || !functionContext.targetFunction().getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    describeContext(functionContext) + " must remain shell-only before body lowering"
            );
        }
    }

    private static @NotNull String describeContext(@NotNull FunctionLoweringContext functionContext) {
        return "Function lowering context "
                + functionContext.kind()
                + " "
                + functionContext.owningClass().getName()
                + "."
                + functionContext.targetFunction().getName();
    }

    private static final class BodyLoweringSession {
        private final FunctionLoweringContext functionContext;
        private final FrontendAnalysisData analysisData;
        private final FrontendCfgGraph graph;
        private final LirFunctionDef function;
        private final SequencedMap<String, GdType> valueTypes;
        private final Set<String> mergeValueIds;

        private BodyLoweringSession(@NotNull FunctionLoweringContext functionContext) {
            this.functionContext = Objects.requireNonNull(functionContext, "functionContext must not be null");
            this.analysisData = functionContext.analysisData();
            this.graph = functionContext.requireFrontendCfgGraph();
            this.function = functionContext.targetFunction();
            this.valueTypes = FrontendBodyLoweringSupport.collectCfgValueSlotTypes(graph, analysisData);
            this.mergeValueIds = collectMergeValueIds(graph);
        }

        private void run() {
            requireShellOnlyTarget(functionContext);
            declareSelfSlotIfNeeded();
            declareSourceLocalSlots();
            declareCfgValueSlots();
            createBlocks();
            lowerBlocks();
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
                switch (graph.requireNode(nodeId)) {
                    case FrontendCfgGraph.SequenceNode(_, var items, var nextId) -> {
                        for (var item : items) {
                            lowerSequenceItem(block, item);
                        }
                        block.setTerminator(new GotoInsn(nextId));
                    }
                    case FrontendCfgGraph.BranchNode(_, _, var conditionValueId, var trueTargetId, var falseTargetId) ->
                            lowerBranchNode(block, conditionValueId, trueTargetId, falseTargetId);
                    case FrontendCfgGraph.StopNode(_, var returnValueIdOrNull) ->
                            block.setTerminator(new ReturnInsn(slotIdForValueOrNull(returnValueIdOrNull)));
                }
            }
        }

        private void lowerBranchNode(
                @NotNull LirBasicBlock block,
                @NotNull String conditionValueId,
                @NotNull String trueTargetId,
                @NotNull String falseTargetId
        ) {
            FrontendBodyLoweringSupport.emitConditionBranch(
                    function,
                    block,
                    conditionValueId,
                    requireValueType(conditionValueId),
                    trueTargetId,
                    falseTargetId
            );
        }

        private void lowerSequenceItem(@NotNull LirBasicBlock block, @NotNull SequenceItem item) {
            switch (item) {
                case SourceAnchorItem sourceAnchorItem -> lowerSourceAnchorItem(block, sourceAnchorItem);
                case LocalDeclarationItem localDeclarationItem ->
                        lowerLocalDeclarationItem(block, localDeclarationItem);
                case BoolConstantItem boolConstantItem -> lowerBoolConstantItem(block, boolConstantItem);
                case MergeValueItem mergeValueItem -> lowerMergeValueItem(block, mergeValueItem);
                case OpaqueExprValueItem opaqueExprValueItem -> lowerOpaqueExprValueItem(block, opaqueExprValueItem);
                case CallItem callItem -> lowerCallItem(block, callItem);
                case MemberLoadItem memberLoadItem -> lowerMemberLoadItem(block, memberLoadItem);
                case SubscriptLoadItem subscriptLoadItem -> lowerSubscriptLoadItem(block, subscriptLoadItem);
                case AssignmentItem assignmentItem -> lowerAssignmentItem(block, assignmentItem);
                case CastItem castItem ->
                        throw unsupportedSequenceItem(castItem, "cast lowering is not implemented yet");
                case TypeTestItem typeTestItem ->
                        throw unsupportedSequenceItem(typeTestItem, "type-test lowering is not implemented yet");
            }
        }

        private void lowerSourceAnchorItem(@NotNull LirBasicBlock block, @NotNull SourceAnchorItem item) {
            var lineNumber = sourceLine(item.statement());
            if (lineNumber > 0) {
                block.appendNonTerminatorInstruction(new LineNumberInsn(lineNumber));
            }
        }

        private void lowerLocalDeclarationItem(@NotNull LirBasicBlock block, @NotNull LocalDeclarationItem item) {
            var initializerValueId = item.initializerValueIdOrNull();
            if (initializerValueId == null) {
                return;
            }
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.sourceLocalSlotId(item.declaration()),
                    slotIdForValue(initializerValueId)
            ));
        }

        private void lowerBoolConstantItem(@NotNull LirBasicBlock block, @NotNull BoolConstantItem item) {
            block.appendNonTerminatorInstruction(new LiteralBoolInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId()),
                    item.value()
            ));
        }

        private void lowerMergeValueItem(@NotNull LirBasicBlock block, @NotNull MergeValueItem item) {
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.mergeSlotId(item.resultValueId()),
                    slotIdForValue(item.sourceValueId())
            ));
        }

        private void lowerOpaqueExprValueItem(@NotNull LirBasicBlock block, @NotNull OpaqueExprValueItem item) {
            var policy = FrontendBodyLoweringSupport.classifyOpaqueExpression(item.expression());
            if (policy.handling() != FrontendBodyLoweringSupport.OpaqueExprHandling.HANDLE_NOW) {
                throw unsupportedSequenceItem(item, policy.detail());
            }
            switch (item.expression()) {
                case IdentifierExpression identifierExpression ->
                        lowerIdentifierValue(block, item, identifierExpression);
                case LiteralExpression literalExpression -> lowerLiteralValue(block, item, literalExpression);
                case SelfExpression _ ->
                        block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId(item), "self"));
                case UnaryExpression unaryExpression -> lowerUnaryValue(block, item, unaryExpression);
                case BinaryExpression binaryExpression -> lowerBinaryValue(block, item, binaryExpression);
                default -> throw unsupportedSequenceItem(
                        item,
                        "opaque expression root is not supported by body lowering: "
                                + item.expression().getClass().getSimpleName()
                );
            }
        }

        private void lowerIdentifierValue(
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem item,
                @NotNull IdentifierExpression identifierExpression
        ) {
            var binding = requireBinding(identifierExpression);
            var resultSlotId = resultSlotId(item);
            switch (binding.kind()) {
                case SELF -> block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, "self"));
                case LOCAL_VAR, PARAMETER, CAPTURE ->
                        block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, binding.symbolName()));
                case PROPERTY -> {
                    if (isStaticPropertyBinding(binding)) {
                        block.appendNonTerminatorInstruction(new LoadStaticInsn(
                                resultSlotId,
                                currentClassName(),
                                binding.symbolName()
                        ));
                        return;
                    }
                    requireSelfSlot();
                    block.appendNonTerminatorInstruction(new LoadPropertyInsn(resultSlotId, binding.symbolName(), "self"));
                }
                default -> throw unsupportedSequenceItem(
                        item,
                        "identifier binding kind is not supported by executable body lowering: " + binding.kind()
                );
            }
        }

        private void lowerLiteralValue(
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem item,
                @NotNull LiteralExpression literalExpression
        ) {
            var resultSlotId = resultSlotId(item);
            switch (literalExpression.kind()) {
                case "integer" -> block.appendNonTerminatorInstruction(new LiteralIntInsn(
                        resultSlotId,
                        Integer.parseInt(literalExpression.sourceText())
                ));
                case "number" -> {
                    if (literalExpression.sourceText().contains(".")) {
                        block.appendNonTerminatorInstruction(new LiteralFloatInsn(
                                resultSlotId,
                                Double.parseDouble(literalExpression.sourceText())
                        ));
                        return;
                    }
                    block.appendNonTerminatorInstruction(new LiteralIntInsn(
                            resultSlotId,
                            Integer.parseInt(literalExpression.sourceText())
                    ));
                }
                case "float" -> block.appendNonTerminatorInstruction(new LiteralFloatInsn(
                        resultSlotId,
                        Double.parseDouble(literalExpression.sourceText())
                ));
                case "string" -> block.appendNonTerminatorInstruction(new LiteralStringInsn(
                        resultSlotId,
                        literalExpression.sourceText()
                ));
                case "string_name" -> block.appendNonTerminatorInstruction(new LiteralStringNameInsn(
                        resultSlotId,
                        literalExpression.sourceText()
                ));
                case "true" -> block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, true));
                case "false" -> block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, false));
                case "null" -> block.appendNonTerminatorInstruction(new LiteralNilInsn(resultSlotId));
                default -> throw unsupportedSequenceItem(
                        item,
                        "literal kind is not supported by executable body lowering: " + literalExpression.kind()
                );
            }
        }

        private void lowerUnaryValue(
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem item,
                @NotNull UnaryExpression unaryExpression
        ) {
            requireOperandCount(item, 1);
            block.appendNonTerminatorInstruction(new UnaryOpInsn(
                    resultSlotId(item),
                    GodotOperator.fromSourceLexeme(unaryExpression.operator(), GodotOperator.OperatorArity.UNARY),
                    slotIdForValue(item.operandValueIds().getFirst())
            ));
        }

        private void lowerBinaryValue(
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem item,
                @NotNull BinaryExpression binaryExpression
        ) {
            requireOperandCount(item, 2);
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    resultSlotId(item),
                    GodotOperator.fromSourceLexeme(binaryExpression.operator(), GodotOperator.OperatorArity.BINARY),
                    slotIdForValue(item.operandValueIds().getFirst()),
                    slotIdForValue(item.operandValueIds().getLast())
            ));
        }

        private void lowerCallItem(@NotNull LirBasicBlock block, @NotNull CallItem item) {
            var resolvedCall = requireResolvedCall(item.anchor());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId());
            var arguments = variableOperands(item.argumentValueIds());
            switch (resolvedCall.callKind()) {
                case INSTANCE_METHOD -> block.appendNonTerminatorInstruction(new CallMethodInsn(
                        resultSlotId,
                        resolvedCall.callableName(),
                        resolveInstanceCallReceiver(item),
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
                            requireClassName(resolvedCall.receiverType()),
                            resolvedCall.callableName(),
                            arguments
                    ));
                }
                case CONSTRUCTOR -> throw unsupportedSequenceItem(
                        item,
                        "constructor route lowering is not implemented yet"
                );
                case UNKNOWN, DYNAMIC_FALLBACK -> throw unsupportedSequenceItem(
                        item,
                        "call route is not lowering-ready: " + resolvedCall.callKind()
                );
            }
        }

        private void lowerMemberLoadItem(@NotNull LirBasicBlock block, @NotNull MemberLoadItem item) {
            var resolvedMember = requireResolvedMember(item.anchor());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId());
            switch (resolvedMember.receiverKind()) {
                case INSTANCE -> block.appendNonTerminatorInstruction(new LoadPropertyInsn(
                        resultSlotId,
                        item.memberName(),
                        slotIdForValue(item.baseValueId())
                ));
                case TYPE_META -> block.appendNonTerminatorInstruction(new LoadStaticInsn(
                        resultSlotId,
                        requireClassName(resolvedMember.receiverType()),
                        item.memberName()
                ));
                default -> throw unsupportedSequenceItem(
                        item,
                        "member receiver kind is not lowering-ready: " + resolvedMember.receiverKind()
                );
            }
        }

        private void lowerSubscriptLoadItem(@NotNull LirBasicBlock block, @NotNull SubscriptLoadItem item) {
            requireSingleSubscriptArgument(item.anchor(), item.argumentValueIds());
            var baseSlotId = slotIdForValue(item.baseValueId());
            var keyValueId = item.argumentValueIds().getFirst();
            var keySlotId = slotIdForValue(item.argumentValueIds().getFirst());
            var keyType = requireValueType(keyValueId);
            if (item.memberNameOrNull() == null) {
                appendSubscriptLoadInstruction(
                        block,
                        FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId()),
                        baseSlotId,
                        requireValueType(item.baseValueId()),
                        keySlotId,
                        keyType
                );
                return;
            }

            var namedBaseSlotId = namedBaseSlotId(item.resultValueId());
            var nameSlotId = namedKeySlotId(item.resultValueId());
            block.appendNonTerminatorInstruction(new LiteralStringNameInsn(nameSlotId, item.memberNameOrNull()));
            block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                    namedBaseSlotId,
                    baseSlotId,
                    nameSlotId
            ));
            appendSubscriptLoadInstruction(
                    block,
                    FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId()),
                    namedBaseSlotId,
                    GdVariantType.VARIANT,
                    keySlotId,
                    keyType
            );
        }

        /// Assignment lowering is allowed to inspect the target AST shape only to choose the final
        /// store route. All child evaluation order and operand materialization must already be frozen
        /// into `targetOperandValueIds` plus `rhsValueId`.
        private void lowerAssignmentItem(@NotNull LirBasicBlock block, @NotNull AssignmentItem item) {
            var rhsSlotId = slotIdForValue(item.rhsValueId());
            switch (item.assignment().left()) {
                case IdentifierExpression identifierExpression ->
                        lowerIdentifierAssignment(block, item, identifierExpression, rhsSlotId);
                case AttributeExpression attributeExpression ->
                        lowerAttributeAssignment(block, item, attributeExpression, rhsSlotId);
                case SubscriptExpression subscriptExpression ->
                        lowerSubscriptAssignment(block, item, subscriptExpression, rhsSlotId);
                default -> throw unsupportedSequenceItem(
                        item,
                        "assignment target is not supported by executable body lowering: "
                                + item.assignment().left().getClass().getSimpleName()
                );
            }

            if (item.resultValueIdOrNull() != null) {
                block.appendNonTerminatorInstruction(new AssignInsn(
                        FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueIdOrNull()),
                        rhsSlotId
                ));
            }
        }

        private void lowerIdentifierAssignment(
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem item,
                @NotNull IdentifierExpression identifierExpression,
                @NotNull String rhsSlotId
        ) {
            if (!item.targetOperandValueIds().isEmpty()) {
                throw unsupportedSequenceItem(item, "identifier assignment must not publish target operand values");
            }
            var binding = requireBinding(identifierExpression);
            switch (binding.kind()) {
                case LOCAL_VAR, PARAMETER, CAPTURE ->
                        block.appendNonTerminatorInstruction(new AssignInsn(binding.symbolName(), rhsSlotId));
                case PROPERTY -> {
                    if (isStaticPropertyBinding(binding)) {
                        block.appendNonTerminatorInstruction(new StoreStaticInsn(
                                currentClassName(),
                                binding.symbolName(),
                                rhsSlotId
                        ));
                        return;
                    }
                    requireSelfSlot();
                    block.appendNonTerminatorInstruction(new StorePropertyInsn(binding.symbolName(), "self", rhsSlotId));
                }
                default -> throw unsupportedSequenceItem(
                        item,
                        "identifier assignment binding kind is not supported: " + binding.kind()
                );
            }
        }

        private void lowerAttributeAssignment(
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem item,
                @NotNull AttributeExpression attributeExpression,
                @NotNull String rhsSlotId
        ) {
            if (attributeExpression.steps().isEmpty()) {
                throw unsupportedSequenceItem(item, "attribute assignment target must contain at least one step");
            }
            switch (attributeExpression.steps().getLast()) {
                case AttributePropertyStep attributePropertyStep ->
                        lowerAttributePropertyAssignment(block, item, attributePropertyStep, rhsSlotId);
                case AttributeSubscriptStep attributeSubscriptStep ->
                        lowerAttributeSubscriptAssignment(block, item, attributeSubscriptStep, rhsSlotId);
                default -> throw unsupportedSequenceItem(
                        item,
                        "attribute assignment step is not supported: "
                                + attributeExpression.steps().getLast().getClass().getSimpleName()
                );
            }
        }

        private void lowerAttributePropertyAssignment(
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem item,
                @NotNull AttributePropertyStep attributePropertyStep,
                @NotNull String rhsSlotId
        ) {
            if (item.targetOperandValueIds().size() != 1) {
                throw unsupportedSequenceItem(item, "attribute property assignment must publish exactly one receiver");
            }
            var receiverSlotId = slotIdForValue(item.targetOperandValueIds().getFirst());
            var resolvedMember = requireResolvedMember(attributePropertyStep);
            switch (resolvedMember.receiverKind()) {
                case INSTANCE -> block.appendNonTerminatorInstruction(new StorePropertyInsn(
                        attributePropertyStep.name(),
                        receiverSlotId,
                        rhsSlotId
                ));
                case TYPE_META -> block.appendNonTerminatorInstruction(new StoreStaticInsn(
                        requireClassName(resolvedMember.receiverType()),
                        attributePropertyStep.name(),
                        rhsSlotId
                ));
                default -> throw unsupportedSequenceItem(
                        item,
                        "attribute property assignment receiver kind is not lowering-ready: "
                                + resolvedMember.receiverKind()
                );
            }
        }

        private void lowerAttributeSubscriptAssignment(
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem item,
                @NotNull AttributeSubscriptStep attributeSubscriptStep,
                @NotNull String rhsSlotId
        ) {
            requireSingleSubscriptArgument(item.anchor(), attributeSubscriptStep.arguments());
            if (item.targetOperandValueIds().size() != 2) {
                throw unsupportedSequenceItem(
                        item,
                        "attribute subscript assignment must publish receiver plus one key operand"
                );
            }
            var receiverSlotId = slotIdForValue(item.targetOperandValueIds().getFirst());
            var keyValueId = item.targetOperandValueIds().getLast();
            var keySlotId = slotIdForValue(keyValueId);
            var namedBaseSlotId = namedBaseSlotId("assign_" + item.rhsValueId());
            var nameSlotId = namedKeySlotId("assign_" + item.rhsValueId());
            block.appendNonTerminatorInstruction(new LiteralStringNameInsn(nameSlotId, attributeSubscriptStep.name()));
            block.appendNonTerminatorInstruction(new VariantGetNamedInsn(namedBaseSlotId, receiverSlotId, nameSlotId));
            appendSubscriptStoreInstruction(
                    block,
                    namedBaseSlotId,
                    GdVariantType.VARIANT,
                    keySlotId,
                    requireValueType(keyValueId),
                    rhsSlotId
            );
            block.appendNonTerminatorInstruction(new VariantSetNamedInsn(receiverSlotId, nameSlotId, namedBaseSlotId));
        }

        private void lowerSubscriptAssignment(
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem item,
                @NotNull SubscriptExpression subscriptExpression,
                @NotNull String rhsSlotId
        ) {
            requireSingleSubscriptArgument(item.anchor(), subscriptExpression.arguments());
            if (item.targetOperandValueIds().size() != 2) {
                throw unsupportedSequenceItem(item, "subscript assignment must publish base plus one key operand");
            }
            var baseValueId = item.targetOperandValueIds().getFirst();
            var keyValueId = item.targetOperandValueIds().getLast();
            var baseSlotId = slotIdForValue(baseValueId);
            var keySlotId = slotIdForValue(keyValueId);
            appendSubscriptStoreInstruction(
                    block,
                    baseSlotId,
                    requireValueType(baseValueId),
                    keySlotId,
                    requireValueType(keyValueId),
                    rhsSlotId
            );
            writeBackPropertyBaseIfNeeded(block, subscriptExpression.base(), baseSlotId);
        }

        private void appendSubscriptLoadInstruction(
                @NotNull LirBasicBlock block,
                @NotNull String resultSlotId,
                @NotNull String receiverSlotId,
                @NotNull GdType receiverType,
                @NotNull String keySlotId,
                @NotNull GdType keyType
        ) {
            switch (FrontendBodyLoweringSupport.chooseSubscriptAccessKind(receiverType, keyType)) {
                case GENERIC -> block.appendNonTerminatorInstruction(new VariantGetInsn(
                        resultSlotId,
                        receiverSlotId,
                        keySlotId
                ));
                case KEYED -> block.appendNonTerminatorInstruction(new VariantGetKeyedInsn(
                        resultSlotId,
                        receiverSlotId,
                        keySlotId
                ));
                case NAMED -> block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                        resultSlotId,
                        receiverSlotId,
                        keySlotId
                ));
                case INDEXED -> block.appendNonTerminatorInstruction(new VariantGetIndexedInsn(
                        resultSlotId,
                        receiverSlotId,
                        keySlotId
                ));
            }
        }

        private void appendSubscriptStoreInstruction(
                @NotNull LirBasicBlock block,
                @NotNull String receiverSlotId,
                @NotNull GdType receiverType,
                @NotNull String keySlotId,
                @NotNull GdType keyType,
                @NotNull String rhsSlotId
        ) {
            switch (FrontendBodyLoweringSupport.chooseSubscriptAccessKind(receiverType, keyType)) {
                case GENERIC -> block.appendNonTerminatorInstruction(new VariantSetInsn(
                        receiverSlotId,
                        keySlotId,
                        rhsSlotId
                ));
                case KEYED -> block.appendNonTerminatorInstruction(new VariantSetKeyedInsn(
                        receiverSlotId,
                        keySlotId,
                        rhsSlotId
                ));
                case NAMED -> block.appendNonTerminatorInstruction(new VariantSetNamedInsn(
                        receiverSlotId,
                        keySlotId,
                        rhsSlotId
                ));
                case INDEXED -> block.appendNonTerminatorInstruction(new VariantSetIndexedInsn(
                        receiverSlotId,
                        keySlotId,
                        rhsSlotId
                ));
            }
        }

        private void writeBackPropertyBaseIfNeeded(
                @NotNull LirBasicBlock block,
                @NotNull Expression baseExpression,
                @NotNull String baseSlotId
        ) {
            if (!(baseExpression instanceof IdentifierExpression identifierExpression)) {
                return;
            }
            var binding = requireBinding(identifierExpression);
            if (binding.kind() != FrontendBindingKind.PROPERTY) {
                return;
            }
            if (isStaticPropertyBinding(binding)) {
                block.appendNonTerminatorInstruction(new StoreStaticInsn(
                        currentClassName(),
                        binding.symbolName(),
                        baseSlotId
                ));
                return;
            }
            requireSelfSlot();
            block.appendNonTerminatorInstruction(new StorePropertyInsn(binding.symbolName(), "self", baseSlotId));
        }

        private @NotNull FrontendBinding requireBinding(@NotNull Node useSite) {
            var binding = analysisData.symbolBindings().get(Objects.requireNonNull(useSite, "useSite must not be null"));
            if (binding == null) {
                throw new IllegalStateException("Missing published symbol binding for " + useSite.getClass().getSimpleName());
            }
            return binding;
        }

        private @NotNull FrontendResolvedCall requireResolvedCall(@NotNull Node callAnchor) {
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

        private @NotNull FrontendResolvedMember requireResolvedMember(@NotNull Node memberAnchor) {
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

        private void requireOperandCount(@NotNull OpaqueExprValueItem item, int expectedCount) {
            if (item.operandValueIds().size() != expectedCount) {
                throw unsupportedSequenceItem(
                        item,
                        "expected " + expectedCount + " operand value ids, but got " + item.operandValueIds().size()
                );
            }
        }

        private void requireSingleSubscriptArgument(@NotNull Node anchor, @NotNull List<?> arguments) {
            if (arguments.size() != 1) {
                throw new IllegalStateException(
                        "Subscript lowering currently supports exactly one key operand for "
                                + anchor.getClass().getSimpleName()
                );
            }
        }

        private @NotNull String resolveInstanceCallReceiver(@NotNull CallItem item) {
            var receiverValueId = item.receiverValueIdOrNull();
            if (receiverValueId != null) {
                return slotIdForValue(receiverValueId);
            }
            requireSelfSlot();
            return "self";
        }

        private void requireSelfSlot() {
            if (function.getVariableById("self") == null) {
                throw new IllegalStateException(
                        describeContext(functionContext) + " requires an implicit self receiver slot"
                );
            }
        }

        private void ensureVariable(@NotNull String variableId, @NotNull GdType expectedType) {
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

        private @NotNull LirBasicBlock requireBlock(@NotNull String blockId) {
            var block = function.getBasicBlock(blockId);
            if (block == null) {
                throw new IllegalStateException("LIR basic block has not been materialized: " + blockId);
            }
            return block;
        }

        private @NotNull GdType requireValueType(@NotNull String valueId) {
            var valueType = valueTypes.get(Objects.requireNonNull(valueId, "valueId must not be null"));
            if (valueType == null) {
                throw new IllegalStateException("Missing published type for frontend CFG value id '" + valueId + "'");
            }
            return valueType;
        }

        private @NotNull String slotIdForValue(@NotNull String valueId) {
            return mergeValueIds.contains(valueId)
                    ? FrontendBodyLoweringSupport.mergeSlotId(valueId)
                    : FrontendBodyLoweringSupport.cfgTempSlotId(valueId);
        }

        private @Nullable String slotIdForValueOrNull(@Nullable String valueIdOrNull) {
            return valueIdOrNull == null ? null : slotIdForValue(valueIdOrNull);
        }

        private @NotNull String resultSlotId(@NotNull OpaqueExprValueItem item) {
            return FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId());
        }

        private @NotNull List<LirInstruction.Operand> variableOperands(@NotNull List<String> valueIds) {
            var operands = new ArrayList<LirInstruction.Operand>(valueIds.size());
            for (var valueId : valueIds) {
                operands.add(new LirInstruction.VariableOperand(slotIdForValue(valueId)));
            }
            return List.copyOf(operands);
        }

        private boolean isStaticPropertyBinding(@NotNull FrontendBinding binding) {
            return binding.declarationSite() instanceof VariableDeclaration variableDeclaration
                    && variableDeclaration.isStatic();
        }

        private @NotNull String currentClassName() {
            return functionContext.owningClass().getName();
        }

        private @NotNull String requireClassName(@Nullable GdType receiverType) {
            return switch (Objects.requireNonNull(receiverType, "receiverType must not be null")) {
                case GdObjectType(var className) -> className;
                default -> throw new IllegalStateException(
                        "Static receiver type must be an object/class type, but was " + receiverType.getTypeName()
                );
            };
        }

        private @NotNull String namedBaseSlotId(@NotNull String valueId) {
            var slotId = FrontendBodyLoweringSupport.cfgTempSlotId(valueId) + "_named_base";
            ensureVariable(slotId, GdVariantType.VARIANT);
            return slotId;
        }

        private @NotNull String namedKeySlotId(@NotNull String valueId) {
            var slotId = FrontendBodyLoweringSupport.cfgTempSlotId(valueId) + "_named_key";
            ensureVariable(slotId, GdStringNameType.STRING_NAME);
            return slotId;
        }

        private static int sourceLine(@NotNull Statement statement) {
            var range = statement.range();
            return range == null ? -1 : range.startPoint().row() + 1;
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

        private static @NotNull IllegalStateException unsupportedSequenceItem(
                @NotNull SequenceItem item,
                @NotNull String detail
        ) {
            return new IllegalStateException(
                    item.getClass().getSimpleName()
                            + " is not supported by frontend body lowering yet: "
                            + detail
            );
        }
    }
}
