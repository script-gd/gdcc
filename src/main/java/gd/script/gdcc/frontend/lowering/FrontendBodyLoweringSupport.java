package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.*;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendSubscriptSemanticSupport;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.Node;
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

    public enum CfgValueMaterializationKind {
        TEMP_SLOT,
        MERGE_SLOT,
        SOURCE_SLOT_ALIAS
    }

    /// Published runtime facts for one frontend CFG value id.
    ///
    /// Most produced value ids still lower into one temp-backed slot, but merge results and
    /// direct-slot aliases intentionally do not share that default shape.
    public record CfgValueMaterialization(
            @NotNull GdType type,
            @NotNull CfgValueMaterializationKind kind,
            @Nullable Node aliasSourceAnchorOrNull
    ) {
        public CfgValueMaterialization {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            if (kind == CfgValueMaterializationKind.SOURCE_SLOT_ALIAS && aliasSourceAnchorOrNull == null) {
                throw new IllegalArgumentException("SOURCE_SLOT_ALIAS materialization requires aliasSourceAnchorOrNull");
            }
            if (kind != CfgValueMaterializationKind.SOURCE_SLOT_ALIAS && aliasSourceAnchorOrNull != null) {
                throw new IllegalArgumentException(
                        kind + " materialization must not carry aliasSourceAnchorOrNull"
                );
            }
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

    /// Collects runtime materialization facts for all frontend CFG value ids.
    ///
    /// This pass intentionally consumes the graph in published node/item order. Graph publication
    /// therefore must already have enforced the branch-result merge rule that every `MergeValueItem`
    /// sources a value produced earlier in the same sequence node; this collector does not try to
    /// recover from cross-sequence merge dependencies.
    public static @NotNull SequencedMap<String, CfgValueMaterialization> collectCfgValueMaterializations(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var materializations = new LinkedHashMap<String, CfgValueMaterialization>();
        for (var nodeId : graph.nodeIds()) {
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, var items, _) -> {
                    for (var item : items) {
                        if (item instanceof ValueOpItem valueOpItem) {
                            collectProducedValueMaterialization(
                                    materializations,
                                    valueOpItem,
                                    analysisData,
                                    classRegistry
                            );
                        }
                    }
                }
                case FrontendCfgGraph.BranchNode _, FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        return materializations;
    }

    /// Convenience projection for callers that only need the published runtime type.
    public static @NotNull SequencedMap<String, GdType> collectCfgValueSlotTypes(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
        var valueTypes = new LinkedHashMap<String, GdType>();
        for (var entry : collectCfgValueMaterializations(graph, analysisData, classRegistry).entrySet()) {
            valueTypes.put(entry.getKey(), entry.getValue().type());
        }
        return valueTypes;
    }

    private static void collectProducedValueMaterialization(
            @NotNull SequencedMap<String, CfgValueMaterialization> materializations,
            @NotNull ValueOpItem item,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
        var resultValueId = item.resultValueIdOrNull();
        if (resultValueId == null) {
            return;
        }
        var materialization = requireProducedValueMaterialization(item, analysisData, classRegistry, materializations);
        var previous = materializations.putIfAbsent(resultValueId, materialization);
        if (previous != null && !previous.equals(materialization)) {
            throw new IllegalStateException(
                    "Conflicting published materializations for value id '"
                            + resultValueId
                            + "': "
                            + previous
                            + " vs "
                            + materialization
            );
        }
    }

    private static @NotNull CfgValueMaterialization requireProducedValueMaterialization(
            @NotNull ValueOpItem item,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry,
            @NotNull SequencedMap<String, CfgValueMaterialization> resolvedMaterializations
    ) {
        var materialization = switch (item) {
            case BoolConstantItem _ -> new CfgValueMaterialization(
                    GdBoolType.BOOL,
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case MergeValueItem mergeValueItem -> new CfgValueMaterialization(
                    requireResolvedValueType(resolvedMaterializations, mergeValueItem.sourceValueId()),
                    CfgValueMaterializationKind.MERGE_SLOT,
                    null
            );
            case DirectSlotAliasValueItem aliasValueItem -> new CfgValueMaterialization(
                    requireOpaqueValueType(analysisData, aliasValueItem.expression()),
                    CfgValueMaterializationKind.SOURCE_SLOT_ALIAS,
                    aliasValueItem.expression()
            );
            case OpaqueExprValueItem opaqueExprValueItem -> new CfgValueMaterialization(
                    requireOpaqueValueType(analysisData, opaqueExprValueItem.expression()),
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case CompoundAssignmentBinaryOpItem compoundAssignmentItem -> new CfgValueMaterialization(
                    requireCompoundAssignmentResultType(compoundAssignmentItem, classRegistry, resolvedMaterializations),
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case CallItem callItem -> new CfgValueMaterialization(
                    requireCallReturnType(analysisData, callItem.anchor()),
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case MemberLoadItem memberLoadItem -> new CfgValueMaterialization(
                    requireMemberResultType(analysisData, memberLoadItem.anchor()),
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case SubscriptLoadItem subscriptLoadItem -> new CfgValueMaterialization(
                    requireSubscriptResultType(analysisData, classRegistry, subscriptLoadItem, resolvedMaterializations),
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case CastItem castItem -> new CfgValueMaterialization(
                    requireExpressionType(analysisData, castItem.expression()),
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case TypeTestItem _ -> new CfgValueMaterialization(
                    GdBoolType.BOOL,
                    CfgValueMaterializationKind.TEMP_SLOT,
                    null
            );
            case AssignmentItem assignmentItem ->
                    new CfgValueMaterialization(
                            requireAssignmentResultType(analysisData, assignmentItem),
                            CfgValueMaterializationKind.TEMP_SLOT,
                            null
                    );
            case LocalDeclarationItem _ ->
                    throw new IllegalStateException("LocalDeclarationItem must not publish a result value id");
        };
        if ((materialization.kind() == CfgValueMaterializationKind.TEMP_SLOT
                || materialization.kind() == CfgValueMaterializationKind.MERGE_SLOT)
                && materialization.type() instanceof GdVoidType) {
            throw new IllegalStateException(
                    "Frontend CFG value '"
                            + Objects.requireNonNull(item.resultValueIdOrNull(), "void materialization requires a result value id")
                            + "' must not materialize as standalone "
                            + materialization.kind()
                            + " with void type for "
                            + describeProducedValue(item)
                            + "; statement-position RESOLVED(void) calls must omit resultValueIdOrNull(), and value-required void calls should have been rejected before body lowering"
            );
        }
        return materialization;
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

    private static @NotNull String describeProducedValue(@NotNull ValueOpItem item) {
        return switch (item) {
            case CallItem callItem -> "call '" + callItem.callableName() + "'";
            default -> item.getClass().getSimpleName() + " anchored at " + item.anchor().getClass().getSimpleName();
        };
    }

    private static @NotNull GdType requireCallReturnType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node callAnchor
    ) {
        var anchor = Objects.requireNonNull(callAnchor, "callAnchor must not be null");
        return requireLoweringReadyExpressionType(
                analysisData,
                anchor,
                describeCallAnchor(anchor)
        );
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
            @NotNull SequencedMap<String, CfgValueMaterialization> resolvedMaterializations
    ) {
        if (subscriptLoadItem.anchor() instanceof Expression expression) {
            if (analysisData.expressionTypes().get(expression) == null) {
                return requireCompoundAssignmentSubscriptResultType(
                        classRegistry,
                        subscriptLoadItem,
                        resolvedMaterializations
                );
            }
            return requireExpressionType(analysisData, expression);
        }
        if (subscriptLoadItem.anchor() instanceof AttributeSubscriptStep attributeSubscriptStep) {
            if (analysisData.expressionTypes().get(attributeSubscriptStep) == null) {
                return requireCompoundAssignmentSubscriptResultType(
                        classRegistry,
                        subscriptLoadItem,
                        resolvedMaterializations
                );
            }
            return requireLoweringReadyExpressionType(
                    analysisData,
                    attributeSubscriptStep,
                    "AttributeSubscriptStep '" + attributeSubscriptStep.name() + "[...]'"
            );
        }
        throw new IllegalStateException(
                "Unsupported subscript anchor: " + subscriptLoadItem.anchor().getClass().getSimpleName()
        );
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
            @NotNull AssignmentItem assignmentItem
    ) {
        if (assignmentItem.resultValueIdOrNull() == null) {
            throw new IllegalStateException("AssignmentItem must not request a result type without a result value id");
        }
        return requireExpressionType(analysisData, assignmentItem.assignment());
    }

    /// Call result runtime types are published through `expressionTypes()`, not re-derived from
    /// `resolvedCalls()`. This matters especially for `DYNAMIC` routes, whose runtime type contract
    /// is the published `Variant` expression type even though the resolved-call fact carries no exact
    /// signature return metadata.
    private static @NotNull String describeCallAnchor(@NotNull Node callAnchor) {
        return switch (callAnchor) {
            case AttributeCallStep step -> "AttributeCallStep '" + step.name() + "()'";
            case CallExpression _ -> "CallExpression";
            default -> callAnchor.getClass().getSimpleName();
        };
    }

    private static @NotNull GdType requireResolvedValueType(
            @NotNull SequencedMap<String, CfgValueMaterialization> resolvedMaterializations,
            @NotNull String valueId
    ) {
        var resolvedMaterialization = resolvedMaterializations.get(StringUtil.requireNonBlank(valueId, "valueId"));
        if (resolvedMaterialization == null) {
            throw new IllegalStateException(
                    "Missing previously resolved value type for '"
                            + valueId
                            + "'. FrontendCfgGraph publication should have rejected any merge item that depends "
                            + "on a not-yet-published source value."
            );
        }
        return resolvedMaterialization.type();
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
            @NotNull SequencedMap<String, CfgValueMaterialization> resolvedMaterializations
    ) {
        var currentTargetType = requireCompoundOperandValueType(
                resolvedMaterializations,
                compoundAssignmentItem.currentTargetValueId(),
                "current target"
        );
        var rhsType = requireCompoundOperandValueType(
                resolvedMaterializations,
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
            @NotNull SequencedMap<String, CfgValueMaterialization> resolvedMaterializations,
            @NotNull String valueId,
            @NotNull String role
    ) {
        var resolvedMaterialization = resolvedMaterializations.get(StringUtil.requireNonBlank(valueId, "valueId"));
        if (resolvedMaterialization != null) {
            return resolvedMaterialization.type();
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
            @NotNull SequencedMap<String, CfgValueMaterialization> resolvedMaterializations
    ) {
        var receiverType = subscriptLoadItem.memberNameOrNull() == null
                ? requireResolvedValueType(resolvedMaterializations, subscriptLoadItem.baseValueId())
                : GdVariantType.VARIANT;
        var argumentTypes = subscriptLoadItem.argumentValueIds().stream()
                .map(valueId -> requireResolvedValueType(resolvedMaterializations, valueId))
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
