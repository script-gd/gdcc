package dev.superice.gdcc.frontend.lowering;

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
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

public final class FrontendBodyLoweringSupport {
    private FrontendBodyLoweringSupport() {
    }

    public static @NotNull String cfgTempSlotId(@NotNull String valueId) {
        return "cfg_tmp_" + StringUtil.requireNonBlank(valueId, "valueId");
    }

    public static @NotNull String mergeSlotId(@NotNull String valueId) {
        return "cfg_merge_" + StringUtil.requireNonBlank(valueId, "valueId");
    }

    public static @NotNull String sourceLocalSlotId(@NotNull VariableDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        return StringUtil.requireNonBlank(declaration.name(), "declaration.name()");
    }

    public static @NotNull String conditionVariantSlotId(@NotNull String valueId) {
        return "cfg_cond_variant_" + StringUtil.requireNonBlank(valueId, "valueId");
    }

    public static @NotNull String conditionBoolSlotId(@NotNull String valueId) {
        return "cfg_cond_bool_" + StringUtil.requireNonBlank(valueId, "valueId");
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
                    "Conflicting published types for value id '"
                            + resultValueId
                            + "': "
                            + previous.getTypeName()
                            + " vs "
                            + resolvedType.getTypeName()
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
                    "Missing published expression type for "
                            + StringUtil.requireNonBlank(anchorDescription, "anchorDescription")
            );
        }
        if (publishedType.publishedType() != null) {
            return publishedType.publishedType();
        }
        var detailReason = publishedType.detailReason();
        var detailSuffix = detailReason == null || detailReason.isBlank() ? "" : ": " + detailReason;
        throw new IllegalStateException(
                StringUtil.requireNonBlank(anchorDescription, "anchorDescription")
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
        var resolvedType = resolvedValueTypes.get(StringUtil.requireNonBlank(valueId, "valueId"));
        if (resolvedType == null) {
            throw new IllegalStateException("Missing previously resolved value type for '" + valueId + "'");
        }
        return resolvedType;
    }
}
