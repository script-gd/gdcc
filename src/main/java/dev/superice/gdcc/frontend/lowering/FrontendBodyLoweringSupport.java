package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CastItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendSubscriptSemanticSupport;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
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

    /// Collects runtime slot types for all frontend CFG value ids.
    ///
    /// This pass intentionally consumes the graph in published node/item order. Graph publication
    /// therefore must already have enforced the branch-result merge rule that every `MergeValueItem`
    /// sources a value produced earlier in the same sequence node; this collector does not try to
    /// recover from cross-sequence merge dependencies.
    public static @NotNull SequencedMap<String, GdType> collectCfgValueSlotTypes(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var valueTypes = new LinkedHashMap<String, GdType>();
        for (var nodeId : graph.nodeIds()) {
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, var items, _) -> {
                    for (var item : items) {
                        if (item instanceof ValueOpItem valueOpItem) {
                            collectProducedValueType(valueTypes, valueOpItem, analysisData, classRegistry);
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
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
        var resultValueId = item.resultValueIdOrNull();
        if (resultValueId == null) {
            return;
        }
        var resolvedType = requireProducedValueType(item, analysisData, classRegistry, valueTypes);
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
            @NotNull ClassRegistry classRegistry,
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
            case CompoundAssignmentBinaryOpItem compoundAssignmentItem -> requireCompoundAssignmentResultType(
                    compoundAssignmentItem,
                    classRegistry,
                    resolvedValueTypes
            );
            case CallItem callItem -> requireCallReturnType(analysisData, callItem.anchor());
            case MemberLoadItem memberLoadItem -> requireMemberResultType(analysisData, memberLoadItem.anchor());
            case SubscriptLoadItem subscriptLoadItem -> requireSubscriptResultType(
                    analysisData,
                    classRegistry,
                    subscriptLoadItem,
                    resolvedValueTypes
            );
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
            @NotNull ClassRegistry classRegistry,
            @NotNull SubscriptLoadItem subscriptLoadItem,
            @NotNull SequencedMap<String, GdType> resolvedValueTypes
    ) {
        if (subscriptLoadItem.anchor() instanceof Expression expression) {
            if (analysisData.expressionTypes().get(expression) == null) {
                return requireCompoundAssignmentSubscriptResultType(classRegistry, subscriptLoadItem, resolvedValueTypes);
            }
            return requireExpressionType(analysisData, expression);
        }
        if (subscriptLoadItem.anchor() instanceof AttributeSubscriptStep attributeSubscriptStep) {
            if (analysisData.expressionTypes().get(attributeSubscriptStep) == null) {
                return requireCompoundAssignmentSubscriptResultType(classRegistry, subscriptLoadItem, resolvedValueTypes);
            }
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
        if (binding != null && binding.kind() == FrontendBindingKind.PROPERTY
                && binding.declarationSite() instanceof PropertyDef propertyDef) {
            return propertyDef.getType();
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
            throw new IllegalStateException(
                    "Missing previously resolved value type for '"
                            + valueId
                            + "'. FrontendCfgGraph publication should have rejected any merge item that depends "
                            + "on a not-yet-published source value."
            );
        }
        return resolvedType;
    }

    /// Compound-assignment result slots must carry the real binary-operation result type instead of
    /// blindly reusing the final store target type.
    ///
    /// This keeps `Variant`-to-concrete boundaries anchored at the later assignment store contract:
    /// if `count += value` produces a dynamic `Variant` result, the binary temp stays `Variant` and
    /// the eventual unpack still happens at the ordinary assignment boundary rather than being
    /// accidentally pulled forward into `BinaryOpInsn`.
    private static @NotNull GdType requireCompoundAssignmentResultType(
            @NotNull CompoundAssignmentBinaryOpItem compoundAssignmentItem,
            @NotNull ClassRegistry classRegistry,
            @NotNull SequencedMap<String, GdType> resolvedValueTypes
    ) {
        var currentTargetType = requireCompoundOperandValueType(
                resolvedValueTypes,
                compoundAssignmentItem.currentTargetValueId(),
                "current target"
        );
        var rhsType = requireCompoundOperandValueType(
                resolvedValueTypes,
                compoundAssignmentItem.rhsValueId(),
                "rhs"
        );
        var binaryResultType = FrontendExpressionSemanticSupport.resolveBinaryOperatorResultType(
                classRegistry,
                compoundAssignmentItem.binaryOperatorLexeme(),
                FrontendExpressionType.resolved(currentTargetType),
                FrontendExpressionType.resolved(rhsType)
        );
        if (binaryResultType.status() == FrontendExpressionTypeStatus.RESOLVED
                || binaryResultType.status() == FrontendExpressionTypeStatus.DYNAMIC) {
            return Objects.requireNonNull(
                    binaryResultType.publishedType(),
                    "successful compound assignment result type must publish a runtime type"
            );
        }
        var detailSuffix = binaryResultType.detailReason() == null || binaryResultType.detailReason().isBlank()
                ? ""
                : ": " + binaryResultType.detailReason();
        throw new IllegalStateException(
                "Compound assignment body-lowering contract published unsupported binary operator or non-lowering-ready "
                        + "result status '"
                        + binaryResultType.status()
                        + "' for operator '"
                        + compoundAssignmentItem.binaryOperatorLexeme()
                        + "'"
                        + detailSuffix
        );
    }

    private static @NotNull GdType requireCompoundOperandValueType(
            @NotNull SequencedMap<String, GdType> resolvedValueTypes,
            @NotNull String valueId,
            @NotNull String role
    ) {
        var resolvedType = resolvedValueTypes.get(StringUtil.requireNonBlank(valueId, "valueId"));
        if (resolvedType != null) {
            return resolvedType;
        }
        throw new IllegalStateException(
                "Compound assignment body-lowering contract is missing the published "
                        + StringUtil.requireNonBlank(role, "role")
                        + " value type for '"
                        + valueId
                        + "'"
        );
    }

    /// Subscript reads that originate from compound-assignment targets are allowed to bypass the
    /// ordinary `expressionTypes()` publication table, mirroring the CFG-builder contract that
    /// assignment targets need only the frozen receiver/key operand types and must not require a
    /// second ordinary-value publication route.
    private static @NotNull GdType requireCompoundAssignmentSubscriptResultType(
            @NotNull ClassRegistry classRegistry,
            @NotNull SubscriptLoadItem subscriptLoadItem,
            @NotNull SequencedMap<String, GdType> resolvedValueTypes
    ) {
        var receiverType = subscriptLoadItem.memberNameOrNull() == null
                ? requireResolvedValueType(resolvedValueTypes, subscriptLoadItem.baseValueId())
                : GdVariantType.VARIANT;
        var argumentTypes = subscriptLoadItem.argumentValueIds().stream()
                .map(valueId -> requireResolvedValueType(resolvedValueTypes, valueId))
                .toList();
        var resolvedType = new FrontendSubscriptSemanticSupport(classRegistry).resolveSubscriptType(
                receiverType,
                argumentTypes,
                "compound-assignment current-target subscript read"
        );
        if (resolvedType.status() == FrontendExpressionTypeStatus.RESOLVED
                || resolvedType.status() == FrontendExpressionTypeStatus.DYNAMIC) {
            return Objects.requireNonNull(
                    resolvedType.publishedType(),
                    "successful compound-assignment subscript read must publish a runtime type"
            );
        }
        throw new IllegalStateException(
                "Compound assignment body-lowering contract published non-lowering-ready subscript result status '"
                        + resolvedType.status()
                        + "'"
        );
    }
}
