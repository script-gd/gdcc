package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.PropertyDefAccessSupport;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/// Shared assignment semantic helper used by both body-phase analyzers.
///
/// Assignment is intentionally modeled outside `FrontendExpressionSemanticSupport` because the left
/// side is not an ordinary value expression:
/// - target resolution needs its own writable-slot model
/// - successful assignment publishes `RESOLVED(void)` instead of the RHS type
/// - value-required contexts must fail closed after the target and RHS are otherwise valid
public final class FrontendAssignmentSemanticSupport {
    public enum AssignmentUsage {
        STATEMENT_ROOT,
        VALUE_REQUIRED
    }

    public enum AssignmentTargetKind {
        IDENTIFIER,
        ATTRIBUTE_PROPERTY,
        SUBSCRIPT,
        ATTRIBUTE_SUBSCRIPT
    }

    public enum AssignmentTargetStatus {
        RESOLVED,
        BLOCKED,
        DEFERRED,
        DYNAMIC,
        FAILED,
        UNSUPPORTED
    }

    public record AssignmentTargetResult(
            @NotNull AssignmentTargetStatus status,
            @NotNull AssignmentTargetKind targetKind,
            @Nullable GdType slotType,
            @Nullable String detailReason,
            boolean rootOwnsOutcome
    ) {
        public AssignmentTargetResult {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(targetKind, "targetKind must not be null");
            switch (status) {
                case RESOLVED -> {
                    Objects.requireNonNull(slotType, "slotType must not be null for resolved assignment targets");
                    if (detailReason != null) {
                        throw new IllegalArgumentException(
                                "detailReason must be null for resolved assignment targets"
                        );
                    }
                }
                case DYNAMIC -> {
                    Objects.requireNonNull(slotType, "slotType must not be null for dynamic assignment targets");
                    StringUtil.requireNonBlank(detailReason, "detailReason");
                    if (!(slotType instanceof GdVariantType)) {
                        throw new IllegalArgumentException("dynamic assignment targets must publish Variant");
                    }
                }
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> {
                    if (slotType != null) {
                        throw new IllegalArgumentException(
                                "slotType must be null for %s assignment targets".formatted(status)
                        );
                    }
                    StringUtil.requireNonBlank(detailReason, "detailReason");
                }
            }
        }

        public static @NotNull AssignmentTargetResult resolved(
                @NotNull AssignmentTargetKind targetKind,
                @NotNull GdType slotType
        ) {
            return new AssignmentTargetResult(
                    AssignmentTargetStatus.RESOLVED,
                    targetKind,
                    Objects.requireNonNull(slotType, "slotType must not be null"),
                    null,
                    false
            );
        }

        public static @NotNull AssignmentTargetResult blocked(
                @NotNull AssignmentTargetKind targetKind,
                boolean rootOwnsOutcome,
                @NotNull String detailReason
        ) {
            return new AssignmentTargetResult(
                    AssignmentTargetStatus.BLOCKED,
                    targetKind,
                    null,
                    detailReason,
                    rootOwnsOutcome
            );
        }

        public static @NotNull AssignmentTargetResult deferred(
                @NotNull AssignmentTargetKind targetKind,
                boolean rootOwnsOutcome,
                @NotNull String detailReason
        ) {
            return new AssignmentTargetResult(
                    AssignmentTargetStatus.DEFERRED,
                    targetKind,
                    null,
                    detailReason,
                    rootOwnsOutcome
            );
        }

        public static @NotNull AssignmentTargetResult dynamic(
                @NotNull AssignmentTargetKind targetKind,
                @NotNull String detailReason
        ) {
            return new AssignmentTargetResult(
                    AssignmentTargetStatus.DYNAMIC,
                    targetKind,
                    GdVariantType.VARIANT,
                    detailReason,
                    false
            );
        }

        public static @NotNull AssignmentTargetResult failed(
                @NotNull AssignmentTargetKind targetKind,
                boolean rootOwnsOutcome,
                @NotNull String detailReason
        ) {
            return new AssignmentTargetResult(
                    AssignmentTargetStatus.FAILED,
                    targetKind,
                    null,
                    detailReason,
                    rootOwnsOutcome
            );
        }

        public static @NotNull AssignmentTargetResult unsupported(
                @NotNull AssignmentTargetKind targetKind,
                boolean rootOwnsOutcome,
                @NotNull String detailReason
        ) {
            return new AssignmentTargetResult(
                    AssignmentTargetStatus.UNSUPPORTED,
                    targetKind,
                    null,
                    detailReason,
                    rootOwnsOutcome
            );
        }
    }

    private enum PropertyFailureOwnership {
        CHAIN_OWNED,
        ASSIGNMENT_OWNED_BLOCKED_FAILED
    }

    private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull FrontendModuleSkeleton moduleSkeleton;
    private final @NotNull Supplier<ResolveRestriction> restrictionSupplier;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull FrontendChainReductionFacade chainReduction;
    private final @NotNull FrontendSubscriptSemanticSupport subscriptSemanticSupport;

    public FrontendAssignmentSemanticSupport(
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull FrontendModuleSkeleton moduleSkeleton,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendChainReductionFacade chainReduction
    ) {
        this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.moduleSkeleton = Objects.requireNonNull(moduleSkeleton, "moduleSkeleton must not be null");
        this.restrictionSupplier = Objects.requireNonNull(restrictionSupplier, "restrictionSupplier must not be null");
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.chainReduction = Objects.requireNonNull(chainReduction, "chainReduction must not be null");
        subscriptSemanticSupport = new FrontendSubscriptSemanticSupport(this.classRegistry);
    }

    public @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult resolveAssignmentExpressionType(
            @NotNull AssignmentExpression assignmentExpression,
            @NotNull AssignmentUsage usage,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var targetResult = resolveAssignmentTarget(assignmentExpression.left(), nestedResolver, finalizeWindow);
        var targetIssue = toTargetIssue(targetResult);
        var rightType = nestedResolver.resolve(assignmentExpression.right(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(rightType);
        if (targetIssue != null) {
            return expressionResult(targetIssue, targetResult.rootOwnsOutcome());
        }
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        if (!"=".equals(assignmentExpression.operator())) {
            return rootOutcome(FrontendExpressionType.unsupported(
                    "Compound assignment operator '" + assignmentExpression.operator()
                            + "' is not supported by the current frontend assignment contract"
            ));
        }

        var valueType = Objects.requireNonNull(rightType.publishedType(), "publishedType must not be null");
        var compatibilityIssue = resolveAssignmentCompatibilityIssue(targetResult, valueType);
        if (compatibilityIssue != null) {
            return rootOutcome(compatibilityIssue);
        }
        if (usage == AssignmentUsage.VALUE_REQUIRED) {
            return rootOutcome(FrontendExpressionType.failed(
                    "Assignment expressions do not produce an ordinary value in this position"
            ));
        }
        return propagated(FrontendExpressionType.resolved(GdVoidType.VOID));
    }

    /// The left side is resolved through a dedicated writable-target model instead of the ordinary
    /// expression resolver. This keeps assignment-target semantics independent from ordinary value
    /// typing and prevents left-value rules from drifting with read/call semantics.
    public @NotNull AssignmentTargetResult resolveAssignmentTarget(
            @NotNull Expression targetExpression,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        return switch (Objects.requireNonNull(targetExpression, "targetExpression must not be null")) {
            case IdentifierExpression identifierExpression -> resolveIdentifierTarget(identifierExpression);
            case AttributeExpression attributeExpression -> resolveAttributeTarget(
                    attributeExpression,
                    nestedResolver,
                    finalizeWindow
            );
            case SubscriptExpression subscriptExpression -> resolveSubscriptTarget(
                    subscriptExpression,
                    nestedResolver,
                    finalizeWindow
            );
            default -> AssignmentTargetResult.unsupported(
                    AssignmentTargetKind.SUBSCRIPT,
                    true,
                    "Assignment target kind '" + targetExpression.getClass().getSimpleName()
                            + "' is not supported by the current frontend assignment contract"
            );
        };
    }

    private @NotNull AssignmentTargetResult resolveIdentifierTarget(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = scopesByAst.get(identifierExpression);
        if (currentScope == null) {
            return AssignmentTargetResult.unsupported(
                    AssignmentTargetKind.IDENTIFIER,
                    false,
                    "Assignment target '" + identifierExpression.name() + "' is inside a skipped subtree"
            );
        }

        var valueResult = currentScope.resolveValue(identifierExpression.name(), currentRestriction());
        if (valueResult.isAllowed()) {
            return resolveWritableIdentifierValue(identifierExpression, valueResult.requireValue());
        }
        if (valueResult.isBlocked()) {
            return AssignmentTargetResult.blocked(
                    AssignmentTargetKind.IDENTIFIER,
                    false,
                    "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
            );
        }

        var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction());
        if (functionResult.isBlocked()) {
            return AssignmentTargetResult.blocked(
                    AssignmentTargetKind.IDENTIFIER,
                    false,
                    "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
            );
        }
        if (functionResult.isAllowed()) {
            return AssignmentTargetResult.failed(
                    AssignmentTargetKind.IDENTIFIER,
                    true,
                    "Binding '" + identifierExpression.name()
                            + "' resolves to a Callable reference and cannot be assigned"
            );
        }

        var typeMetaResult = moduleSkeleton.resolveSourceFacingTypeMeta(
                currentScope,
                identifierExpression.name(),
                currentRestriction()
        );
        if (typeMetaResult.isAllowed()) {
            return AssignmentTargetResult.failed(
                    AssignmentTargetKind.IDENTIFIER,
                    true,
                    "Type-meta '" + identifierExpression.name() + "' is not an assignable storage location"
            );
        }

        var publishedBinding = symbolBindings.get(identifierExpression);
        var detailReason = publishedBinding == null || publishedBinding.kind() == FrontendBindingKind.UNKNOWN
                ? "No published assignment-target binding fact is available for identifier '"
                  + identifierExpression.name() + "'"
                : "Published assignment-target binding '" + identifierExpression.name()
                  + "' is no longer visible";
        return AssignmentTargetResult.failed(AssignmentTargetKind.IDENTIFIER, false, detailReason);
    }

    private @NotNull AssignmentTargetResult resolveWritableIdentifierValue(
            @NotNull IdentifierExpression identifierExpression,
            @NotNull ScopeValue scopeValue
    ) {
        if (scopeValue.kind() == ScopeValueKind.PROPERTY) {
            return classifyDirectPropertyTarget(
                    AssignmentTargetKind.IDENTIFIER,
                    identifierExpression.name(),
                    scopeValue.type(),
                    scopeValue.writable()
            );
        }
        if (scopeValue.kind() == ScopeValueKind.SIGNAL) {
            return AssignmentTargetResult.failed(
                    AssignmentTargetKind.IDENTIFIER,
                    true,
                    "Signal '" + identifierExpression.name() + "' is read-only and cannot be assigned"
            );
        }
        if (scopeValue.constant()) {
            return AssignmentTargetResult.failed(
                    AssignmentTargetKind.IDENTIFIER,
                    true,
                    "Binding '" + identifierExpression.name() + "' is read-only and cannot be assigned"
            );
        }
        return AssignmentTargetResult.resolved(AssignmentTargetKind.IDENTIFIER, scopeValue.type());
    }

    private @NotNull AssignmentTargetResult resolveAttributeTarget(
            @NotNull AttributeExpression attributeExpression,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var finalStep = attributeExpression.steps().getLast();
        return switch (finalStep) {
            case AttributePropertyStep _ -> resolvePropertyLikeAttributeTarget(
                    attributeExpression,
                    AssignmentTargetKind.ATTRIBUTE_PROPERTY,
                    PropertyFailureOwnership.CHAIN_OWNED
            );
            case AttributeSubscriptStep attributeSubscriptStep -> resolveAttributeSubscriptTarget(
                    attributeExpression,
                    attributeSubscriptStep,
                    nestedResolver,
                    finalizeWindow
            );
            default -> AssignmentTargetResult.unsupported(
                    AssignmentTargetKind.ATTRIBUTE_SUBSCRIPT,
                    true,
                    "Assignment target step '" + finalStep.getClass().getSimpleName()
                            + "' is not supported by the current frontend assignment contract"
            );
        };
    }

    private @NotNull AssignmentTargetResult resolveSubscriptTarget(
            @NotNull SubscriptExpression subscriptExpression,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var baseReceiver = chainReduction.headReceiverSupport().resolveHeadReceiver(subscriptExpression.base());
        var baseIssue = toBaseReceiverIssue(AssignmentTargetKind.SUBSCRIPT, baseReceiver);
        if (baseIssue != null) {
            return baseIssue;
        }

        var receiverState = Objects.requireNonNull(baseReceiver, "baseReceiver must not be null");
        if (receiverState.receiverKind() == FrontendReceiverKind.TYPE_META) {
            return AssignmentTargetResult.failed(
                    AssignmentTargetKind.SUBSCRIPT,
                    true,
                    "Type-meta receivers cannot be used as subscript assignment targets"
            );
        }

        var argumentResolution = resolveArgumentTypes(subscriptExpression.arguments(), nestedResolver, finalizeWindow);
        if (argumentResolution.issue() != null) {
            return targetFromDependency(AssignmentTargetKind.SUBSCRIPT, argumentResolution.issue());
        }

        var subscriptType = subscriptSemanticSupport.resolveSubscriptType(
                Objects.requireNonNull(receiverState.receiverType(), "receiverType must not be null"),
                argumentResolution.argumentTypes(),
                "subscript assignment target"
        );
        return targetFromSubscriptSemantic(AssignmentTargetKind.SUBSCRIPT, subscriptType, true);
    }

    private @NotNull AssignmentTargetResult resolveAttributeSubscriptTarget(
            @NotNull AttributeExpression attributeExpression,
            @NotNull AttributeSubscriptStep attributeSubscriptStep,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        // The current contract models `obj.prop[i] = value` as element mutation over the resolved
        // property value type. Getter-only properties do not yet add a second aliasing-specific
        // block here; that boundary remains coupled to the broader property/container mutation model.
        var propertyTarget = resolvePropertyLikeAttributeTarget(
                syntheticPropertyExpression(attributeExpression, attributeSubscriptStep),
                AssignmentTargetKind.ATTRIBUTE_SUBSCRIPT,
                PropertyFailureOwnership.ASSIGNMENT_OWNED_BLOCKED_FAILED
        );
        var propertyIssue = toTargetIssue(propertyTarget);
        if (propertyIssue != null) {
            return propertyTarget;
        }

        var argumentResolution = resolveArgumentTypes(
                attributeSubscriptStep.arguments(),
                nestedResolver,
                finalizeWindow
        );
        if (argumentResolution.issue() != null) {
            return targetFromDependency(AssignmentTargetKind.ATTRIBUTE_SUBSCRIPT, argumentResolution.issue());
        }

        var subscriptType = subscriptSemanticSupport.resolveSubscriptType(
                Objects.requireNonNull(propertyTarget.slotType(), "slotType must not be null"),
                argumentResolution.argumentTypes(),
                "attribute subscript assignment target '" + attributeSubscriptStep.name() + "'"
        );
        return targetFromSubscriptSemantic(AssignmentTargetKind.ATTRIBUTE_SUBSCRIPT, subscriptType, false);
    }

    private @NotNull AssignmentTargetResult resolvePropertyLikeAttributeTarget(
            @NotNull AttributeExpression attributeExpression,
            @NotNull AssignmentTargetKind targetKind,
            @NotNull PropertyFailureOwnership failureOwnership
    ) {
        var reduced = chainReduction.reduce(attributeExpression).result();
        if (reduced == null) {
            return AssignmentTargetResult.unsupported(
                    targetKind,
                    false,
                    "Attribute assignment target is inside an unsupported or skipped subtree"
            );
        }

        var finalTrace = reduced.stepTraces().getLast();
        if (finalTrace.upstreamCause() != null) {
            return targetFromReductionStatus(targetKind, finalTrace.status(), false, finalTrace.detailReason());
        }
        return switch (finalTrace.status()) {
            case RESOLVED -> classifyResolvedPropertyTarget(
                    targetKind,
                    Objects.requireNonNull(finalTrace.suggestedMember(), "suggestedMember must not be null")
            );
            case DYNAMIC -> AssignmentTargetResult.dynamic(
                    targetKind,
                    Objects.requireNonNull(finalTrace.detailReason(), "detailReason must not be null")
            );
            case BLOCKED, FAILED -> targetFromReductionStatus(
                    targetKind,
                    finalTrace.status(),
                    failureOwnership == PropertyFailureOwnership.ASSIGNMENT_OWNED_BLOCKED_FAILED,
                    finalTrace.detailReason()
            );
            case DEFERRED, UNSUPPORTED -> targetFromReductionStatus(
                    targetKind,
                    finalTrace.status(),
                    false,
                    finalTrace.detailReason()
            );
        };
    }

    private @NotNull AssignmentTargetResult classifyResolvedPropertyTarget(
            @NotNull AssignmentTargetKind targetKind,
            @NotNull FrontendResolvedMember resolvedMember
    ) {
        var resultType = Objects.requireNonNull(resolvedMember.resultType(), "resultType must not be null");
        return switch (resolvedMember.bindingKind()) {
            case PROPERTY -> {
                if (!requiresDirectPropertyWriteCheck(targetKind)) {
                    yield AssignmentTargetResult.resolved(targetKind, resultType);
                }
                var declarationSite = resolvedMember.declarationSite();
                if (!(declarationSite instanceof PropertyDef propertyDef)) {
                    yield AssignmentTargetResult.failed(
                            targetKind,
                            true,
                            "Resolved property '" + resolvedMember.memberName()
                                    + "' does not carry property metadata"
                    );
                }
                yield classifyDirectPropertyTarget(
                        targetKind,
                        resolvedMember.memberName(),
                        resultType,
                        PropertyDefAccessSupport.isDirectlyWritable(propertyDef)
                );
            }
            case SIGNAL -> AssignmentTargetResult.failed(
                    targetKind,
                    true,
                    "Signal '" + resolvedMember.memberName() + "' is read-only and cannot be assigned"
            );
            case METHOD, STATIC_METHOD, UTILITY_FUNCTION -> AssignmentTargetResult.failed(
                    targetKind,
                    true,
                    "Member '" + resolvedMember.memberName()
                            + "' resolves to a Callable reference and cannot be assigned"
            );
            case CONSTANT, SINGLETON, GLOBAL_ENUM, TYPE_META, SELF, LITERAL, LOCAL_VAR, PARAMETER,
                 CAPTURE, UNKNOWN -> AssignmentTargetResult.failed(
                    targetKind,
                    true,
                    "Member '" + resolvedMember.memberName()
                            + "' is not an assignable property-backed storage location"
            );
        };
    }

    /// Bare property assignment and attribute-property assignment share the same direct-write
    /// contract. `ATTRIBUTE_SUBSCRIPT` intentionally bypasses this check for now because H2 still
    /// models `obj.prop[i] = value` as container element mutation over the property value.
    private @NotNull AssignmentTargetResult classifyDirectPropertyTarget(
            @NotNull AssignmentTargetKind targetKind,
            @NotNull String propertyName,
            @NotNull GdType slotType,
            boolean writable
    ) {
        if (!writable) {
            return AssignmentTargetResult.failed(
                    targetKind,
                    true,
                    "Property '" + propertyName + "' is not writable"
            );
        }
        return AssignmentTargetResult.resolved(targetKind, slotType);
    }

    private boolean requiresDirectPropertyWriteCheck(@NotNull AssignmentTargetKind targetKind) {
        return targetKind == AssignmentTargetKind.IDENTIFIER
                || targetKind == AssignmentTargetKind.ATTRIBUTE_PROPERTY;
    }

    private @Nullable AssignmentTargetResult toBaseReceiverIssue(
            @NotNull AssignmentTargetKind targetKind,
            @Nullable FrontendChainReductionHelper.ReceiverState receiverState
    ) {
        if (receiverState == null) {
            return AssignmentTargetResult.unsupported(
                    targetKind,
                    false,
                    "Assignment target base is inside an unsupported or skipped subtree"
            );
        }
        return switch (receiverState.status()) {
            case RESOLVED, DYNAMIC -> null;
            case BLOCKED -> AssignmentTargetResult.blocked(
                    targetKind,
                    false,
                    Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
            );
            case DEFERRED -> AssignmentTargetResult.deferred(
                    targetKind,
                    false,
                    Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
            );
            case FAILED -> AssignmentTargetResult.failed(
                    targetKind,
                    false,
                    Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
            );
            case UNSUPPORTED -> AssignmentTargetResult.unsupported(
                    targetKind,
                    false,
                    Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
            );
        };
    }

    private @NotNull AssignmentTargetResult targetFromSubscriptSemantic(
            @NotNull AssignmentTargetKind targetKind,
            @NotNull FrontendExpressionType subscriptType,
            boolean rootOwnsUnsupported
    ) {
        return switch (subscriptType.status()) {
            case RESOLVED -> AssignmentTargetResult.resolved(
                    targetKind,
                    Objects.requireNonNull(subscriptType.publishedType(), "publishedType must not be null")
            );
            case DYNAMIC -> AssignmentTargetResult.dynamic(
                    targetKind,
                    Objects.requireNonNull(subscriptType.detailReason(), "detailReason must not be null")
            );
            case FAILED -> AssignmentTargetResult.failed(
                    targetKind,
                    true,
                    Objects.requireNonNull(subscriptType.detailReason(), "detailReason must not be null")
            );
            case UNSUPPORTED -> AssignmentTargetResult.unsupported(
                    targetKind,
                    rootOwnsUnsupported,
                    Objects.requireNonNull(subscriptType.detailReason(), "detailReason must not be null")
            );
            case BLOCKED, DEFERRED -> throw new IllegalStateException(
                    "subscript typing should not publish " + subscriptType.status()
            );
        };
    }

    private @NotNull AssignmentTargetResult targetFromReductionStatus(
            @NotNull AssignmentTargetKind targetKind,
            @NotNull FrontendChainReductionHelper.Status status,
            boolean rootOwnsOutcome,
            @Nullable String detailReason
    ) {
        var reason = Objects.requireNonNull(detailReason, "detailReason must not be null");
        return switch (status) {
            case BLOCKED -> AssignmentTargetResult.blocked(targetKind, rootOwnsOutcome, reason);
            case DEFERRED -> AssignmentTargetResult.deferred(targetKind, rootOwnsOutcome, reason);
            case FAILED -> AssignmentTargetResult.failed(targetKind, rootOwnsOutcome, reason);
            case UNSUPPORTED -> AssignmentTargetResult.unsupported(targetKind, rootOwnsOutcome, reason);
            case RESOLVED, DYNAMIC -> throw new IllegalStateException("status does not represent a failure");
        };
    }

    private @NotNull AssignmentTargetResult targetFromDependency(
            @NotNull AssignmentTargetKind targetKind,
            @NotNull FrontendExpressionType dependencyIssue
    ) {
        return switch (dependencyIssue.status()) {
            case BLOCKED -> AssignmentTargetResult.blocked(
                    targetKind,
                    false,
                    Objects.requireNonNull(dependencyIssue.detailReason(), "detailReason must not be null")
            );
            case DEFERRED -> AssignmentTargetResult.deferred(
                    targetKind,
                    false,
                    Objects.requireNonNull(dependencyIssue.detailReason(), "detailReason must not be null")
            );
            case FAILED -> AssignmentTargetResult.failed(
                    targetKind,
                    false,
                    Objects.requireNonNull(dependencyIssue.detailReason(), "detailReason must not be null")
            );
            case UNSUPPORTED -> AssignmentTargetResult.unsupported(
                    targetKind,
                    false,
                    Objects.requireNonNull(dependencyIssue.detailReason(), "detailReason must not be null")
            );
            case RESOLVED, DYNAMIC -> throw new IllegalStateException("dependencyIssue must be non-resolved");
        };
    }

    private @Nullable FrontendExpressionType toTargetIssue(@NotNull AssignmentTargetResult targetResult) {
        return switch (targetResult.status()) {
            case RESOLVED, DYNAMIC -> null;
            case BLOCKED -> FrontendExpressionType.blocked(
                    null,
                    Objects.requireNonNull(targetResult.detailReason(), "detailReason must not be null")
            );
            case DEFERRED -> FrontendExpressionType.deferred(
                    Objects.requireNonNull(targetResult.detailReason(), "detailReason must not be null")
            );
            case FAILED -> FrontendExpressionType.failed(
                    Objects.requireNonNull(targetResult.detailReason(), "detailReason must not be null")
            );
            case UNSUPPORTED -> FrontendExpressionType.unsupported(
                    Objects.requireNonNull(targetResult.detailReason(), "detailReason must not be null")
            );
        };
    }

    private @NotNull CallArgumentResolution resolveArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var argumentTypes = new ArrayList<GdType>(arguments.size());
        for (var argument : arguments) {
            var argumentType = nestedResolver.resolve(argument, finalizeWindow);
            switch (argumentType.status()) {
                case RESOLVED, DYNAMIC -> argumentTypes.add(
                        Objects.requireNonNull(argumentType.publishedType(), "publishedType must not be null")
                );
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> {
                    return new CallArgumentResolution(List.of(), argumentType);
                }
            }
        }
        return new CallArgumentResolution(List.copyOf(argumentTypes), null);
    }

    private @Nullable FrontendExpressionType firstNonResolvedDependency(
            @NotNull FrontendExpressionType... dependencies
    ) {
        for (var dependency : dependencies) {
            if (dependency.status() != FrontendExpressionTypeStatus.RESOLVED
                    && dependency.status() != FrontendExpressionTypeStatus.DYNAMIC) {
                return dependency;
            }
        }
        return null;
    }

    /// Public frontend assignment compatibility check for already-resolved concrete slots.
    ///
    /// This API intentionally stays narrower than assignment semantic resolution:
    /// - exact `Variant` targets accept any stable source
    /// - stable `Variant` sources may flow into concrete targets
    /// - `Nil` may flow into object targets and lowering will materialize object-typed `NULL`
    /// - all other pairs stay on the strict shared assignability contract
    ///
    /// Assignment-specific routes such as `DYNAMIC` targets stay encapsulated in this helper and do
    /// not leak into the public API.
    public boolean checkAssignmentCompatible(
            @NotNull GdType slotType,
            @NotNull GdType valueType
    ) {
        return FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(
                classRegistry,
                Objects.requireNonNull(valueType, "valueType must not be null"),
                Objects.requireNonNull(slotType, "slotType must not be null")
        );
    }

    private @Nullable FrontendExpressionType resolveAssignmentCompatibilityIssue(
            @NotNull AssignmentTargetResult targetResult,
            @NotNull GdType valueType
    ) {
        if (targetResult.status() == AssignmentTargetStatus.DYNAMIC) {
            return null;
        }
        var slotType = Objects.requireNonNull(targetResult.slotType(), "slotType must not be null");
        if (checkAssignmentCompatible(slotType, valueType)) {
            return null;
        }
        return FrontendExpressionType.failed(
                "Assignment value type '" + valueType.getTypeName()
                        + "' is not assignable to target slot type '" + slotType.getTypeName() + "'"
        );
    }

    private @NotNull AttributeExpression syntheticPropertyExpression(
            @NotNull AttributeExpression attributeExpression,
            @NotNull AttributeSubscriptStep attributeSubscriptStep
    ) {
        var steps = new ArrayList<AttributeStep>(attributeExpression.steps().size());
        for (var index = 0; index < attributeExpression.steps().size() - 1; index++) {
            steps.add(attributeExpression.steps().get(index));
        }
        steps.add(new AttributePropertyStep(attributeSubscriptStep.name(), attributeSubscriptStep.range()));
        return new AttributeExpression(attributeExpression.base(), List.copyOf(steps), attributeExpression.range());
    }

    private @NotNull ResolveRestriction currentRestriction() {
        return Objects.requireNonNull(restrictionSupplier.get(), "currentRestriction must not be null");
    }

    private static @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult expressionResult(
            @NotNull FrontendExpressionType expressionType,
            boolean rootOwnsOutcome
    ) {
        return new FrontendExpressionSemanticSupport.ExpressionSemanticResult(expressionType, rootOwnsOutcome, null);
    }

    private static @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult propagated(
            @NotNull FrontendExpressionType expressionType
    ) {
        return expressionResult(expressionType, false);
    }

    private static @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult rootOutcome(
            @NotNull FrontendExpressionType expressionType
    ) {
        return expressionResult(expressionType, true);
    }

    private record CallArgumentResolution(
            @NotNull List<GdType> argumentTypes,
            @Nullable FrontendExpressionType issue
    ) {
        private CallArgumentResolution {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }
}
