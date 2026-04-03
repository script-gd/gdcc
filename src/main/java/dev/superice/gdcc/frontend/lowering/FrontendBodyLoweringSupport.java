package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CastItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedArrayType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVectorType;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

public final class FrontendBodyLoweringSupport {
    private FrontendBodyLoweringSupport() {
    }

    public static @NotNull String cfgTempSlotId(@NotNull String valueId) {
        return "cfg_tmp_" + requireNonBlank(valueId, "valueId");
    }

    public static @NotNull String mergeSlotId(@NotNull String valueId) {
        return "cfg_merge_" + requireNonBlank(valueId, "valueId");
    }

    public static @NotNull String sourceLocalSlotId(@NotNull VariableDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        return requireNonBlank(declaration.name(), "declaration.name()");
    }

    public static @NotNull String conditionVariantSlotId(@NotNull String valueId) {
        return "cfg_cond_variant_" + requireNonBlank(valueId, "valueId");
    }

    public static @NotNull String conditionBoolSlotId(@NotNull String valueId) {
        return "cfg_cond_bool_" + requireNonBlank(valueId, "valueId");
    }

    public enum OpaqueExprHandling {
        HANDLE_NOW,
        DEFER,
        REJECT
    }

    public enum SubscriptAccessKind {
        GENERIC,
        KEYED,
        NAMED,
        INDEXED
    }

    public record OpaqueExprPolicy(
            @NotNull OpaqueExprHandling handling,
            @NotNull String detail
    ) {
        public OpaqueExprPolicy {
            Objects.requireNonNull(handling, "handling must not be null");
            detail = requireNonBlank(detail, "detail");
        }
    }

    public record ConditionBranchMaterialization(
            @NotNull String branchInputSlotId,
            @Nullable String variantSlotId,
            @Nullable String boolSlotId
    ) {
        public ConditionBranchMaterialization {
            branchInputSlotId = requireNonBlank(branchInputSlotId, "branchInputSlotId");
            variantSlotId = validateOptionalNonBlank(variantSlotId, "variantSlotId");
            boolSlotId = validateOptionalNonBlank(boolSlotId, "boolSlotId");
        }
    }

    public record ShortCircuitBooleanMaterialization(
            @NotNull String mergeSlotId,
            @NotNull String trueConstantSlotId,
            @NotNull String falseConstantSlotId
    ) {
        public ShortCircuitBooleanMaterialization {
            mergeSlotId = requireNonBlank(mergeSlotId, "mergeSlotId");
            trueConstantSlotId = requireNonBlank(trueConstantSlotId, "trueConstantSlotId");
            falseConstantSlotId = requireNonBlank(falseConstantSlotId, "falseConstantSlotId");
        }
    }

    public static @NotNull GdType requireSourceLocalSlotType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull VariableDeclaration declaration
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(declaration, "declaration must not be null");
        var slotType = analysisData.slotTypes().get(declaration);
        if (slotType == null) {
            throw new IllegalStateException(
                    "Missing published slot type for local variable '" + declaration.name() + "'"
            );
        }
        return slotType;
    }

    public static @NotNull SequencedMap<String, GdType> collectCfgValueSlotTypes(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        var valueTypes = new LinkedHashMap<String, GdType>();
        for (var nodeId : graph.nodeIds()) {
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, var items, _) -> {
                    for (var item : items) {
                        if (item instanceof ValueOpItem valueOpItem) {
                            collectProducedValueType(valueTypes, valueOpItem, analysisData);
                        }
                    }
                }
                case FrontendCfgGraph.BranchNode _, FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        return valueTypes;
    }

    public static @NotNull OpaqueExprPolicy classifyOpaqueExpression(@NotNull Expression expression) {
        Objects.requireNonNull(expression, "expression must not be null");
        return switch (expression) {
            case IdentifierExpression _, LiteralExpression _, SelfExpression _ -> new OpaqueExprPolicy(
                    OpaqueExprHandling.HANDLE_NOW,
                    "leaf values stay on the OpaqueExprValueItem route"
            );
            case UnaryExpression _ -> new OpaqueExprPolicy(
                    OpaqueExprHandling.HANDLE_NOW,
                    "eager unary expressions still lower from OpaqueExprValueItem"
            );
            case BinaryExpression binaryExpression when isShortCircuitBinary(binaryExpression) -> new OpaqueExprPolicy(
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

    /// Picks the most specific index instruction family that the published receiver/key types can
    /// satisfy without re-running semantic inference.
    public static @NotNull SubscriptAccessKind chooseSubscriptAccessKind(
            @NotNull GdType receiverType,
            @NotNull GdType keyType
    ) {
        Objects.requireNonNull(receiverType, "receiverType must not be null");
        Objects.requireNonNull(keyType, "keyType must not be null");
        if (keyType instanceof GdIntType && supportsIndexedSubscript(receiverType)) {
            return SubscriptAccessKind.INDEXED;
        }
        if (keyType instanceof GdStringNameType && supportsNamedSubscript(receiverType)) {
            return SubscriptAccessKind.NAMED;
        }
        if (!(keyType instanceof GdVariantType) && supportsKeyedSubscript(receiverType)) {
            return SubscriptAccessKind.KEYED;
        }
        return SubscriptAccessKind.GENERIC;
    }

    public static @NotNull ConditionBranchMaterialization emitConditionBranch(
            @NotNull LirFunctionDef function,
            @NotNull LirBasicBlock block,
            @NotNull String conditionValueId,
            @NotNull GdType conditionType,
            @NotNull String trueBlockId,
            @NotNull String falseBlockId
    ) {
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(block, "block must not be null");
        conditionValueId = requireNonBlank(conditionValueId, "conditionValueId");
        Objects.requireNonNull(conditionType, "conditionType must not be null");
        trueBlockId = requireNonBlank(trueBlockId, "trueBlockId");
        falseBlockId = requireNonBlank(falseBlockId, "falseBlockId");

        var sourceSlotId = cfgTempSlotId(conditionValueId);
        ensureVariable(function, sourceSlotId, conditionType);
        if (conditionType instanceof GdBoolType) {
            block.setTerminator(new GoIfInsn(sourceSlotId, trueBlockId, falseBlockId));
            return new ConditionBranchMaterialization(sourceSlotId, null, null);
        }

        if (conditionType instanceof GdVariantType) {
            var boolSlotId = conditionBoolSlotId(conditionValueId);
            ensureVariable(function, boolSlotId, GdBoolType.BOOL);
            block.appendNonTerminatorInstruction(new UnpackVariantInsn(boolSlotId, sourceSlotId));
            block.setTerminator(new GoIfInsn(boolSlotId, trueBlockId, falseBlockId));
            return new ConditionBranchMaterialization(sourceSlotId, null, boolSlotId);
        }

        var variantSlotId = conditionVariantSlotId(conditionValueId);
        var boolSlotId = conditionBoolSlotId(conditionValueId);
        ensureVariable(function, variantSlotId, GdVariantType.VARIANT);
        ensureVariable(function, boolSlotId, GdBoolType.BOOL);
        block.appendNonTerminatorInstruction(new PackVariantInsn(variantSlotId, sourceSlotId));
        block.appendNonTerminatorInstruction(new UnpackVariantInsn(boolSlotId, variantSlotId));
        block.setTerminator(new GoIfInsn(boolSlotId, trueBlockId, falseBlockId));
        return new ConditionBranchMaterialization(sourceSlotId, variantSlotId, boolSlotId);
    }

    public static @NotNull ShortCircuitBooleanMaterialization emitShortCircuitBooleanMaterialization(
            @NotNull LirFunctionDef function,
            @NotNull LirBasicBlock trueBlock,
            @NotNull LirBasicBlock falseBlock,
            @NotNull String mergedResultValueId,
            @NotNull String mergeBlockId
    ) {
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(trueBlock, "trueBlock must not be null");
        Objects.requireNonNull(falseBlock, "falseBlock must not be null");
        mergedResultValueId = requireNonBlank(mergedResultValueId, "mergedResultValueId");
        mergeBlockId = requireNonBlank(mergeBlockId, "mergeBlockId");

        var mergeSlotId = mergeSlotId(mergedResultValueId);
        var trueConstantSlotId = cfgTempSlotId(mergedResultValueId + "_true");
        var falseConstantSlotId = cfgTempSlotId(mergedResultValueId + "_false");
        ensureVariable(function, mergeSlotId, GdBoolType.BOOL);
        ensureVariable(function, trueConstantSlotId, GdBoolType.BOOL);
        ensureVariable(function, falseConstantSlotId, GdBoolType.BOOL);

        publishBooleanArm(trueBlock, trueConstantSlotId, true, mergeSlotId, mergeBlockId);
        publishBooleanArm(falseBlock, falseConstantSlotId, false, mergeSlotId, mergeBlockId);
        return new ShortCircuitBooleanMaterialization(mergeSlotId, trueConstantSlotId, falseConstantSlotId);
    }

    private static boolean supportsKeyedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdObjectType;
    }

    private static boolean supportsNamedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdObjectType
                || receiverType instanceof GdStringType
                || receiverType instanceof GdVectorType;
    }

    private static boolean supportsIndexedSubscript(@NotNull GdType receiverType) {
        return receiverType instanceof GdVariantType
                || receiverType instanceof GdArrayType
                || receiverType instanceof GdDictionaryType
                || receiverType instanceof GdStringType
                || receiverType instanceof GdVectorType
                || receiverType instanceof GdPackedArrayType;
    }

    private static void collectProducedValueType(
            @NotNull SequencedMap<String, GdType> valueTypes,
            @NotNull ValueOpItem item,
            @NotNull FrontendAnalysisData analysisData
    ) {
        var resultValueId = item.resultValueIdOrNull();
        if (resultValueId == null) {
            return;
        }
        var resolvedType = requireProducedValueType(item, analysisData, valueTypes);
        var previous = valueTypes.putIfAbsent(resultValueId, resolvedType);
        if (previous != null && !previous.equals(resolvedType)) {
            throw new IllegalStateException(
                    "Conflicting published types for value id '" + resultValueId + "': "
                            + previous.getTypeName() + " vs " + resolvedType.getTypeName()
            );
        }
    }

    private static @NotNull GdType requireProducedValueType(
            @NotNull ValueOpItem item,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull SequencedMap<String, GdType> resolvedValueTypes
    ) {
        return switch (item) {
            case BoolConstantItem _ -> GdBoolType.BOOL;
            case MergeValueItem mergeValueItem -> requireResolvedValueType(
                    resolvedValueTypes,
                    mergeValueItem.sourceValueId()
            );
            case OpaqueExprValueItem opaqueExprValueItem -> requireOpaqueValueType(
                    analysisData,
                    opaqueExprValueItem.expression()
            );
            case CallItem callItem -> requireCallReturnType(analysisData, callItem.anchor());
            case MemberLoadItem memberLoadItem -> requireMemberResultType(analysisData, memberLoadItem.anchor());
            case SubscriptLoadItem subscriptLoadItem -> requireSubscriptResultType(analysisData, subscriptLoadItem);
            case CastItem castItem -> requireExpressionType(analysisData, castItem.expression());
            case TypeTestItem _ -> GdBoolType.BOOL;
            case dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem assignmentItem ->
                    requireAssignmentResultType(analysisData, assignmentItem);
            case dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem _ ->
                    throw new IllegalStateException("LocalDeclarationItem must not publish a result value id");
        };
    }

    private static @NotNull GdType requireExpressionType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression
    ) {
        return requireLoweringReadyExpressionType(
                analysisData,
                Objects.requireNonNull(expression, "expression must not be null"),
                expression.getClass().getSimpleName()
        );
    }

    private static @NotNull GdType requireCallReturnType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node callAnchor
    ) {
        var publishedCall = analysisData.resolvedCalls().get(Objects.requireNonNull(callAnchor, "callAnchor must not be null"));
        if (publishedCall == null || publishedCall.returnType() == null) {
            throw new IllegalStateException(
                    "Missing published call return type for " + callAnchor.getClass().getSimpleName()
            );
        }
        return publishedCall.returnType();
    }

    private static @NotNull GdType requireMemberResultType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node memberAnchor
    ) {
        var publishedMember = analysisData.resolvedMembers().get(
                Objects.requireNonNull(memberAnchor, "memberAnchor must not be null")
        );
        if (publishedMember == null || publishedMember.resultType() == null) {
            throw new IllegalStateException(
                    "Missing published member result type for " + memberAnchor.getClass().getSimpleName()
            );
        }
        return publishedMember.resultType();
    }

    private static @NotNull GdType requireSubscriptResultType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull SubscriptLoadItem subscriptLoadItem
    ) {
        if (subscriptLoadItem.anchor() instanceof Expression expression) {
            return requireExpressionType(analysisData, expression);
        }
        if (subscriptLoadItem.anchor() instanceof AttributeSubscriptStep attributeSubscriptStep) {
            return requireLoweringReadyExpressionType(
                    analysisData,
                    attributeSubscriptStep,
                    "AttributeSubscriptStep '" + attributeSubscriptStep.name() + "[...]'"
            );
        }
        throw new IllegalStateException("Unsupported subscript anchor: " + subscriptLoadItem.anchor().getClass().getSimpleName());
    }

    /// Assignment-target prefixes may materialize identifier/self leaves through the opaque route
    /// before ordinary expression publication exists. Reuse published slot types for those trusted
    /// binding-backed leaves instead of forcing them through the generic expression table.
    private static @NotNull GdType requireOpaqueValueType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression
    ) {
        var publishedType = analysisData.expressionTypes().get(expression);
        if (publishedType != null) {
            return requireLoweringReadyExpressionType(
                    analysisData,
                    expression,
                    expression.getClass().getSimpleName()
            );
        }
        var binding = analysisData.symbolBindings().get(expression);
        if (binding != null && binding.declarationSite() instanceof Node declarationNode) {
            var slotType = analysisData.slotTypes().get(declarationNode);
            if (slotType != null) {
                return slotType;
            }
        }
        return requireExpressionType(analysisData, expression);
    }

    /// Lowering may be invoked from tests, incremental tooling, or other non-compile-gated flows. When
    /// a published expression fact exists but is still FAILED/UNSUPPORTED/etc, surface that exact status
    /// and reason instead of collapsing it into an unhelpful “missing type” failure.
    private static @NotNull GdType requireLoweringReadyExpressionType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node anchor,
            @NotNull String anchorDescription
    ) {
        var publishedType = analysisData.expressionTypes().get(Objects.requireNonNull(anchor, "anchor must not be null"));
        if (publishedType == null) {
            throw new IllegalStateException(
                    "Missing published expression type for " + requireNonBlank(anchorDescription, "anchorDescription")
            );
        }
        if (publishedType.publishedType() != null) {
            return publishedType.publishedType();
        }
        var detailReason = publishedType.detailReason();
        var detailSuffix = detailReason == null || detailReason.isBlank() ? "" : ": " + detailReason;
        throw new IllegalStateException(
                requireNonBlank(anchorDescription, "anchorDescription")
                        + " is not lowering-ready because its published expression type is "
                        + publishedType.status()
                        + detailSuffix
                        + ". FrontendCompileCheckAnalyzer should have blocked this before body lowering."
        );
    }

    private static @NotNull GdType requireAssignmentResultType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem assignmentItem
    ) {
        if (assignmentItem.resultValueIdOrNull() == null) {
            throw new IllegalStateException("AssignmentItem must not request a result type without a result value id");
        }
        return requireExpressionType(analysisData, assignmentItem.assignment());
    }

    private static @NotNull GdType requireResolvedValueType(
            @NotNull SequencedMap<String, GdType> resolvedValueTypes,
            @NotNull String valueId
    ) {
        var resolvedType = resolvedValueTypes.get(requireNonBlank(valueId, "valueId"));
        if (resolvedType == null) {
            throw new IllegalStateException("Missing previously resolved value type for '" + valueId + "'");
        }
        return resolvedType;
    }

    private static void ensureVariable(
            @NotNull LirFunctionDef function,
            @NotNull String variableId,
            @NotNull GdType expectedType
    ) {
        var actualVariableId = requireNonBlank(variableId, "variableId");
        var actualExpectedType = Objects.requireNonNull(expectedType, "expectedType must not be null");
        var existing = function.getVariableById(actualVariableId);
        if (existing == null) {
            function.createAndAddVariable(actualVariableId, actualExpectedType);
            return;
        }
        if (!existing.type().equals(actualExpectedType)) {
            throw new IllegalStateException(
                    "Variable '" + actualVariableId + "' already exists with type "
                            + existing.type().getTypeName()
                            + ", expected "
                            + actualExpectedType.getTypeName()
            );
        }
    }

    private static void publishBooleanArm(
            @NotNull LirBasicBlock block,
            @NotNull String constantSlotId,
            boolean constantValue,
            @NotNull String mergeSlotId,
            @NotNull String mergeBlockId
    ) {
        block.appendNonTerminatorInstruction(new LiteralBoolInsn(constantSlotId, constantValue));
        block.appendNonTerminatorInstruction(new AssignInsn(mergeSlotId, constantSlotId));
        block.setTerminator(new GotoInsn(mergeBlockId));
    }

    private static boolean isShortCircuitBinary(@NotNull BinaryExpression binaryExpression) {
        return resolveShortCircuitOperator(binaryExpression) != null;
    }

    private static @Nullable GodotOperator resolveShortCircuitOperator(@NotNull BinaryExpression binaryExpression) {
        return switch (binaryExpression.operator()) {
            case "and" -> GodotOperator.AND;
            case "or" -> GodotOperator.OR;
            default -> null;
        };
    }

    private static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static @Nullable String validateOptionalNonBlank(@Nullable String value, @NotNull String fieldName) {
        return value == null ? null : requireNonBlank(value, fieldName);
    }
}
