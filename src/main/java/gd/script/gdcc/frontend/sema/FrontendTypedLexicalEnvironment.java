package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.patch.FrontendChainBindingPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendExprTypePatch;
import gd.script.gdcc.frontend.sema.patch.FrontendForIterationResolutionPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendLambdaResolutionPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.sema.patch.FrontendMatchResolutionPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalTypeStabilizationPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendOwnerPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendPatchTransaction;
import gd.script.gdcc.frontend.sema.patch.FrontendPublishedFactTypeGuard;
import gd.script.gdcc.frontend.sema.patch.FrontendTopBindingPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendVarTypePostPatch;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Effective typed view used by the body suite resolver.
///
/// The environment keeps statement-local pending facts separate from current-suite committed facts.
/// Stable side tables and `BlockScope` slots are only changed after exporting a per-owner patch
/// transaction and applying it to `FrontendAnalysisData`. The Interface-layer baseline supplies
/// immutable source-facing slot types until a body fact supersedes them.
///
/// For-iterator declarations use a split `scopesByAst` contract: header vs `FOR_BODY`. Overlay
/// lookup for iterator refinements must use the `FOR_BODY` object identity — see
/// `owningScopeForDeclaration` and `scope_analyzer_implementation.md` §6.1.
public final class FrontendTypedLexicalEnvironment {
    private final @NotNull Scope scope;
    private final @NotNull FrontendAnalysisData stableData;
    private final @Nullable FrontendTypedLexicalEnvironment parent;
    private final @Nullable FrontendTypedLexicalBaseline typedBaseline;
    private final @NotNull OverlayFacts pendingFacts = new OverlayFacts();
    private final @NotNull OverlayFacts committedFacts = new OverlayFacts();

    public FrontendTypedLexicalEnvironment(
            @NotNull Scope scope,
            @NotNull FrontendAnalysisData stableData
    ) {
        this(scope, stableData, null);
    }

    public FrontendTypedLexicalEnvironment(
            @NotNull Scope scope,
            @NotNull FrontendAnalysisData stableData,
            @Nullable FrontendTypedLexicalEnvironment parent
    ) {
        this(scope, stableData, parent, null);
    }

    public FrontendTypedLexicalEnvironment(
            @NotNull Scope scope,
            @NotNull FrontendAnalysisData stableData,
            @Nullable FrontendTypedLexicalEnvironment parent,
            @Nullable FrontendTypedLexicalBaseline typedBaseline
    ) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.stableData = Objects.requireNonNull(stableData, "stableData must not be null");
        this.parent = parent;
        this.typedBaseline = typedBaseline;
    }

    public @NotNull Scope scope() {
        return scope;
    }

    public @Nullable FrontendTypedLexicalEnvironment parent() {
        return parent;
    }

    public @Nullable FrontendBinding symbolBinding(@NotNull Node astNode) {
        var localBinding = firstNonNull(
                pendingFacts.symbolBindings.get(astNode),
                committedFacts.symbolBindings.get(astNode),
                stableData.symbolBindings().get(astNode)
        );
        if (localBinding != null) {
            return effectiveBinding(localBinding);
        }
        return parent != null ? parent.symbolBinding(astNode) : null;
    }

    @SuppressWarnings("unused")
    public @Nullable FrontendResolvedMember resolvedMember(@NotNull Node astNode) {
        return firstNonNull(
                pendingFacts.resolvedMembers.get(astNode),
                committedFacts.resolvedMembers.get(astNode),
                stableData.resolvedMembers().get(astNode),
                parent != null ? parent.resolvedMember(astNode) : null
        );
    }

    @SuppressWarnings("unused")
    public @Nullable FrontendResolvedCall resolvedCall(@NotNull Node astNode) {
        return firstNonNull(
                pendingFacts.resolvedCall(astNode),
                committedFacts.resolvedCall(astNode),
                stableData.resolvedCalls().get(astNode),
                parent != null ? parent.resolvedCall(astNode) : null
        );
    }

    public @Nullable FrontendExpressionType expressionType(@NotNull Node astNode) {
        return firstNonNull(
                pendingFacts.expressionTypes.get(astNode),
                committedFacts.expressionTypes.get(astNode),
                stableData.expressionTypes().get(astNode),
                parent != null ? parent.expressionType(astNode) : null
        );
    }

    public @Nullable FrontendMatchPlan matchPlan(@NotNull Node astNode) {
        return firstNonNull(
                pendingFacts.matchPlans.get(astNode),
                committedFacts.matchPlans.get(astNode),
                stableData.matchPlans().get(astNode),
                parent != null ? parent.matchPlan(astNode) : null
        );
    }

    public @Nullable GdType slotType(@NotNull Node astNode) {
        var localSlotType = firstNonNull(
                pendingFacts.slotTypes.get(astNode),
                committedFacts.slotTypes.get(astNode),
                stableData.slotTypes().get(astNode)
        );
        if (localSlotType != null) {
            return localSlotType;
        }
        if (parent != null) {
            var parentSlotType = parent.slotType(astNode);
            if (parentSlotType != null) {
                return parentSlotType;
            }
        }
        return typedBaseline != null ? typedBaseline.typeFor(astNode) : null;
    }

    /// Returns the effective local value without mutating the owning `BlockScope` slot.
    public @NotNull ScopeValue effectiveScopeValue(@NotNull ScopeValue value, @NotNull Scope owningScope) {
        var checkedValue = Objects.requireNonNull(value, "value must not be null");
        if (!(owningScope instanceof BlockScope blockScope)
                || checkedValue.kind() != ScopeValueKind.LOCAL
                || checkedValue.declaration() == null) {
            return checkedValue;
        }
        var effectiveType = localSlotType(blockScope, checkedValue.name(), checkedValue.declaration());
        if (effectiveType == null || FrontendAnalysisData.sameType(checkedValue.type(), effectiveType)) {
            return checkedValue;
        }
        return withType(checkedValue, effectiveType);
    }

    public @Nullable GdType localSlotType(
            @NotNull BlockScope blockScope,
            @NotNull String name,
            @NotNull Object declaration
    ) {
        var pendingType = pendingFacts.localSlotType(blockScope, name, declaration);
        if (pendingType != null) {
            return pendingType;
        }
        var committedType = committedFacts.localSlotType(blockScope, name, declaration);
        if (committedType != null) {
            return committedType;
        }
        if (parent != null) {
            var parentType = parent.localSlotType(blockScope, name, declaration);
            if (parentType != null) {
                return parentType;
            }
        }
        if (declaration instanceof Node astNode) {
            var publishedType = slotType(astNode);
            if (publishedType != null) {
                return publishedType;
            }
        }
        var stableValue = blockScope.resolveValueHere(name);
        if (stableValue != null && stableValue.declaration() == declaration) {
            return stableValue.type();
        }
        return null;
    }

    /// Declaration-anchored local slot type lookup restricted to flushed overlay updates.
    ///
    /// Lambda capture filling runs mid-suite in the enclosing callable, when the physical slot is
    /// still the inventory baseline. This query only matches already flushed
    /// `LOCAL_TYPE_STABILIZATION` / `FOR_ITERATION_RESOLUTION` updates by scope + declaration
    /// identity along the environment chain; unlike `localSlotType` it never falls back to
    /// `slotType(astNode)` (which would read `VAR_TYPE_POST` / function summaries) or to the
    /// physical slot. Callers substitute the declared/inventory baseline when this returns null.
    public @Nullable GdType declarationSiteLocalSlotType(
            @NotNull BlockScope blockScope,
            @NotNull String name,
            @NotNull Object declaration
    ) {
        var pendingType = pendingFacts.localSlotType(blockScope, name, declaration);
        if (pendingType != null) {
            return pendingType;
        }
        var committedType = committedFacts.localSlotType(blockScope, name, declaration);
        if (committedType != null) {
            return committedType;
        }
        return parent != null ? parent.declarationSiteLocalSlotType(blockScope, name, declaration) : null;
    }

    public void putSymbolBinding(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendBinding binding
    ) {
        requireOwner(owner, FrontendSemanticStage.TOP_BINDING);
        FrontendPublishedFactTypeGuard.checkBinding(binding);
        putSideTable(
                stableData.symbolBindings(),
                committedFacts.symbolBindings,
                pendingFacts.symbolBindings,
                astNode,
                binding,
                "symbolBindings",
                FrontendAnalysisData::sameBinding
        );
    }

    public void addLocalSlotTypeUpdate(
            @NotNull FrontendSemanticStage owner,
            @NotNull FrontendLocalSlotTypeUpdate update
    ) {
        switch (owner) {
            case LOCAL_TYPE_STABILIZATION -> {
                validateLocalSlotTypeUpdate(update);
                pendingFacts.localSlotTypeUpdates.add(update);
            }
            case FOR_ITERATION_RESOLUTION -> {
                validateLocalSlotTypeUpdate(update);
                pendingFacts.forIterationSlotTypeUpdates.add(update);
            }
            case MATCH_PATTERN_RESOLUTION -> {
                validateLocalSlotTypeUpdate(update);
                pendingFacts.matchPatternSlotTypeUpdates.add(update);
            }
            default -> throw FrontendAnalysisData.patchFailure(
                    owner + " cannot publish local slot type updates");
        }
    }

    public void putForIterationPlan(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendForIterationPlan plan
    ) {
        requireOwner(owner, FrontendSemanticStage.FOR_ITERATION_RESOLUTION);
        FrontendPublishedFactTypeGuard.checkForIterationPlan(plan);
        putSideTable(
                stableData.forIterationPlans(),
                committedFacts.forIterationPlans,
                pendingFacts.forIterationPlans,
                astNode,
                plan,
                "forIterationPlans",
                FrontendForIterationPlan::samePlan
        );
    }

    public void putMatchPlan(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendMatchPlan plan
    ) {
        requireOwner(owner, FrontendSemanticStage.MATCH_PATTERN_RESOLUTION);
        FrontendPublishedFactTypeGuard.checkMatchPlan(plan);
        putSideTable(
                stableData.matchPlans(),
                committedFacts.matchPlans,
                pendingFacts.matchPlans,
                astNode,
                plan,
                "matchPlans",
                FrontendMatchPlan::samePlan
        );
    }

    /// Publishes the first complete `FrontendLambdaPlan` for a nested-resolved lambda.
    ///
    /// The plan carries frozen declaration-site capture types; first-wins merge plus `samePlan`
    /// conflict detection reject any later diverging payload for the same lambda node.
    public void putLambdaPlan(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendLambdaPlan plan
    ) {
        requireOwner(owner, FrontendSemanticStage.LAMBDA_RESOLUTION);
        FrontendPublishedFactTypeGuard.checkLambdaPlan(plan);
        putSideTable(
                stableData.lambdaPlans(),
                committedFacts.lambdaPlans,
                pendingFacts.lambdaPlans,
                astNode,
                plan,
                "lambdaPlans",
                FrontendLambdaPlan::samePlan
        );
    }

    public void putResolvedMember(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendResolvedMember member
    ) {
        requireOwner(owner, FrontendSemanticStage.CHAIN_BINDING);
        FrontendPublishedFactTypeGuard.checkResolvedMember(member);
        putSideTable(
                stableData.resolvedMembers(),
                committedFacts.resolvedMembers,
                pendingFacts.resolvedMembers,
                astNode,
                member,
                "resolvedMembers",
                FrontendAnalysisData::sameResolvedMember
        );
    }

    public void putResolvedCall(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendResolvedCall call
    ) {
        FrontendPublishedFactTypeGuard.checkResolvedCall(call);
        switch (owner) {
            case CHAIN_BINDING -> putResolvedCall(pendingFacts.chainResolvedCalls, astNode, call);
            case EXPR_TYPE -> putResolvedCall(pendingFacts.exprResolvedCalls, astNode, call);
            default -> throw FrontendAnalysisData.patchFailure(owner + " cannot publish resolvedCalls() overlay facts");
        }
    }

    public void putExpressionType(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendExpressionType expressionType
    ) {
        requireOwner(owner, FrontendSemanticStage.EXPR_TYPE);
        FrontendPublishedFactTypeGuard.checkExpressionType(expressionType);
        putSideTable(
                stableData.expressionTypes(),
                committedFacts.expressionTypes,
                pendingFacts.expressionTypes,
                astNode,
                expressionType,
                "expressionTypes",
                FrontendAnalysisData::sameExpressionType
        );
    }

    public void putTypeTestTarget(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendTypeTestTarget typeTestTarget
    ) {
        requireOwner(owner, FrontendSemanticStage.EXPR_TYPE);
        FrontendPublishedFactTypeGuard.checkTypeTestTarget(typeTestTarget);
        putSideTable(
                stableData.typeTestTargets(),
                committedFacts.typeTestTargets,
                pendingFacts.typeTestTargets,
                astNode,
                typeTestTarget,
                "typeTestTargets",
                FrontendTypeTestTarget::sameTarget
        );
    }

    public @Nullable FrontendTypeTestTarget typeTestTarget(@NotNull Node astNode) {
        return firstNonNull(
                pendingFacts.typeTestTargets.get(astNode),
                committedFacts.typeTestTargets.get(astNode),
                stableData.typeTestTargets().get(astNode),
                parent != null ? parent.typeTestTarget(astNode) : null
        );
    }

    public void putContainerLiteralPlan(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull FrontendContainerLiteralPlan plan
    ) {
        requireOwner(owner, FrontendSemanticStage.EXPR_TYPE);
        FrontendPublishedFactTypeGuard.checkContainerLiteralPlan(plan);
        putSideTable(
                stableData.containerLiteralPlans(),
                committedFacts.containerLiteralPlans,
                pendingFacts.containerLiteralPlans,
                astNode,
                plan,
                "containerLiteralPlans",
                FrontendContainerLiteralPlan::samePlan
        );
    }

    public @Nullable FrontendContainerLiteralPlan containerLiteralPlan(@NotNull Node astNode) {
        return firstNonNull(
                pendingFacts.containerLiteralPlans.get(astNode),
                committedFacts.containerLiteralPlans.get(astNode),
                stableData.containerLiteralPlans().get(astNode),
                parent != null ? parent.containerLiteralPlan(astNode) : null
        );
    }

    public void putSlotType(
            @NotNull FrontendSemanticStage owner,
            @NotNull Node astNode,
            @NotNull GdType slotType
    ) {
        requireOwner(owner, FrontendSemanticStage.VAR_TYPE_POST);
        FrontendPublishedFactTypeGuard.checkNoCompilerOnlyLeak(slotType, "slotTypes() value");
        putSideTable(
                stableData.slotTypes(),
                committedFacts.slotTypes,
                pendingFacts.slotTypes,
                astNode,
                slotType,
                "slotTypes",
                FrontendAnalysisData::sameType
        );
    }

    /// Moves pending facts into the suite overlay without touching stable data or scopes.
    ///
    /// Callers use this at both statement and callable-entry boundaries.
    public void flushPendingFacts() {
        pendingFacts.checkNoCompilerOnlyLeaks();
        committedFacts.mergeFrom(pendingFacts);
        pendingFacts.clear();
    }

    /// Exports committed suite facts as ordered single-owner patches. Stable data remains unchanged.
    ///
    /// Callable body resolution adds this transaction to its callable-scoped export batch. Property
    /// initializers remain independent roots and apply their transaction directly. Export validates
    /// this environment only; it does not preflight against transactions queued by other suite environments.
    public @NotNull FrontendPatchTransaction exportPatchTransaction() {
        committedFacts.checkNoCompilerOnlyLeaks();
        return new FrontendPatchTransaction(committedFacts.toOwnerPatches());
    }

    public boolean hasPendingFacts() {
        return pendingFacts.hasFacts();
    }

    public boolean hasCommittedFacts() {
        return committedFacts.hasFacts();
    }

    private void putResolvedCall(
            @NotNull FrontendAstSideTable<FrontendResolvedCall> targetTable,
            @NotNull Node astNode,
            @NotNull FrontendResolvedCall call
    ) {
        checkResolvedCallConflict(astNode, call, stableData.resolvedCalls().get(astNode));
        checkResolvedCallConflict(astNode, call, committedFacts.chainResolvedCalls.get(astNode));
        checkResolvedCallConflict(astNode, call, committedFacts.exprResolvedCalls.get(astNode));
        checkResolvedCallConflict(astNode, call, pendingFacts.chainResolvedCalls.get(astNode));
        checkResolvedCallConflict(astNode, call, pendingFacts.exprResolvedCalls.get(astNode));
        targetTable.put(astNode, call);
    }

    private void validateLocalSlotTypeUpdate(@NotNull FrontendLocalSlotTypeUpdate update) {
        FrontendAnalysisData.checkNoVoidLocalSlotType(update.type(), update.name());
        FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdate(update);
        var currentValue = requireEffectiveLocalSlotValue(update);
        if (currentValue.declaration() != update.declaration()) {
            throw FrontendAnalysisData.patchFailure(
                    "local slot update targeted a different declaration for '" + update.name() + "'"
            );
        }
        if (FrontendAnalysisData.sameType(currentValue.type(), update.type())) {
            return;
        }
        if (!(currentValue.type() instanceof GdVariantType)) {
            throw FrontendAnalysisData.patchFailure(
                    "local slot update changed exact type for '"
                            + update.name()
                            + "' from "
                            + currentValue.type().getTypeName()
                            + " to "
                            + update.type().getTypeName()
            );
        }
    }

    private @NotNull ScopeValue requireEffectiveLocalSlotValue(@NotNull FrontendLocalSlotTypeUpdate update) {
        var stableValue = update.scope().resolveValueHere(update.name());
        if (stableValue == null) {
            throw FrontendAnalysisData.patchFailure("local slot update targeted a missing binding for '" + update.name() + "'");
        }
        if (stableValue.kind() != ScopeValueKind.LOCAL) {
            throw FrontendAnalysisData.patchFailure("local slot update targeted a non-local binding for '" + update.name() + "'");
        }
        return effectiveScopeValue(stableValue, update.scope());
    }

    private @NotNull FrontendBinding effectiveBinding(@NotNull FrontendBinding binding) {
        var resolvedValue = binding.resolvedValue();
        if (resolvedValue == null || !(binding.declarationSite() instanceof Node declarationNode)) {
            return binding;
        }
        var owningScope = owningScopeForDeclaration(declarationNode);
        if (owningScope == null) {
            return binding;
        }
        var effectiveValue = effectiveScopeValue(resolvedValue, owningScope);
        return effectiveValue == resolvedValue ? binding : binding.withResolvedValue(effectiveValue);
    }

    /// Resolves the `BlockScope` identity used for local-slot overlays of a declaration site.
    ///
    /// `scopesByAst[ForStatement]` records the header outer scope (iterable/header typing), while
    /// `FOR_ITERATION_RESOLUTION` writes iterator refinements against `scopesByAst[for.body()]`
    /// (`FOR_BODY`). Lookup must use the same object identity so `findLocalSlotTypeUpdate` can match.
    private @Nullable Scope owningScopeForDeclaration(@NotNull Node declarationNode) {
        if (declarationNode instanceof ForStatement forStatement) {
            var bodyScope = stableData.scopesByAst().get(forStatement.body());
            if (bodyScope instanceof BlockScope forBodyScope) {
                return forBodyScope;
            }
        }
        // Pattern binds already have `scopesByAst[PatternBindingExpression]` recorded as the
        // section `MATCH_SECTION_BODY` object (same instance as `scopesByAst[section.body()]`).
        return stableData.scopesByAst().get(declarationNode);
    }

    private static @NotNull ScopeValue withType(@NotNull ScopeValue value, @NotNull GdType type) {
        return new ScopeValue(
                value.name(),
                type,
                value.kind(),
                value.declaration(),
                value.constant(),
                value.writable(),
                value.staticMember()
        );
    }

    private <V> void putSideTable(
            @NotNull FrontendAstSideTable<V> stableTable,
            @NotNull FrontendAstSideTable<V> committedTable,
            @NotNull FrontendAstSideTable<V> pendingTable,
            @NotNull Node astNode,
            @NotNull V value,
            @NotNull String fieldName,
            @NotNull SameValueChecker<V> sameValueChecker
    ) {
        var checkedNode = Objects.requireNonNull(astNode, "astNode must not be null");
        var checkedValue = Objects.requireNonNull(value, "value must not be null");
        checkConflict(fieldName, checkedNode, stableTable.get(checkedNode), checkedValue, sameValueChecker);
        checkConflict(fieldName, checkedNode, committedTable.get(checkedNode), checkedValue, sameValueChecker);
        checkConflict(fieldName, checkedNode, pendingTable.get(checkedNode), checkedValue, sameValueChecker);
        pendingTable.put(checkedNode, checkedValue);
    }

    private static void checkResolvedCallConflict(
            @NotNull Node astNode,
            @NotNull FrontendResolvedCall newValue,
            @Nullable FrontendResolvedCall existingValue
    ) {
        checkConflict(
                "resolvedCalls",
                astNode,
                existingValue,
                newValue,
                FrontendAnalysisData::sameResolvedCall
        );
    }

    private static <V> void checkConflict(
            @NotNull String fieldName,
            @NotNull Node astNode,
            @Nullable V existingValue,
            @NotNull V newValue,
            @NotNull SameValueChecker<V> sameValueChecker
    ) {
        if (existingValue == null || sameValueChecker.sameValue(existingValue, newValue)) {
            return;
        }
        throw FrontendAnalysisData.patchFailure(
                fieldName + " overlay write conflicted on " + FrontendAnalysisData.describeNode(astNode)
        );
    }

    private static void requireOwner(@NotNull FrontendSemanticStage actual, @NotNull FrontendSemanticStage expected) {
        if (actual != expected) {
            throw FrontendAnalysisData.patchFailure(actual + " cannot publish " + expected + " overlay facts");
        }
    }

    @SafeVarargs
    private static <T> @Nullable T firstNonNull(@Nullable T... values) {
        for (var value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface SameValueChecker<V> {
        boolean sameValue(@NotNull V first, @NotNull V second);
    }

    private static final class OverlayFacts {
        private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings = new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers = new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<FrontendResolvedCall> chainResolvedCalls = new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<FrontendResolvedCall> exprResolvedCalls = new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes = new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<GdType> slotTypes = new FrontendAstSideTable<>();
        private final @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates = new ArrayList<>();
        private final @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans = new FrontendAstSideTable<>();
        private final @NotNull List<FrontendLocalSlotTypeUpdate> forIterationSlotTypeUpdates = new ArrayList<>();
        private final @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans = new FrontendAstSideTable<>();
        private final @NotNull List<FrontendLocalSlotTypeUpdate> matchPatternSlotTypeUpdates = new ArrayList<>();
        private final @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets = new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> containerLiteralPlans =
                new FrontendAstSideTable<>();
        private final @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans = new FrontendAstSideTable<>();

        private @Nullable FrontendResolvedCall resolvedCall(@NotNull Node astNode) {
            var chainCall = chainResolvedCalls.get(astNode);
            return chainCall != null ? chainCall : exprResolvedCalls.get(astNode);
        }

        /// Matches by `scope` / `declaration` **object identity** (`==`) and name equality.
        /// For-iterator updates require the `FOR_BODY` instance written by `refineIteratorSlot`.
        private static @Nullable GdType findLocalSlotTypeUpdate(
                @NotNull List<FrontendLocalSlotTypeUpdate> updates,
                @NotNull BlockScope scope,
                @NotNull String name,
                @NotNull Object declaration
        ) {
            for (var i = updates.size() - 1; i >= 0; i--) {
                var update = updates.get(i);
                if (update.scope() == scope
                        && update.declaration() == declaration
                        && update.name().equals(name)) {
                    return update.type();
                }
            }
            return null;
        }

        private @Nullable GdType localSlotType(
                @NotNull BlockScope scope,
                @NotNull String name,
                @NotNull Object declaration
        ) {
            var forIterationType = findLocalSlotTypeUpdate(
                    forIterationSlotTypeUpdates,
                    scope,
                    name,
                    declaration
            );
            if (forIterationType != null) {
                return forIterationType;
            }
            var matchPatternType = findLocalSlotTypeUpdate(
                    matchPatternSlotTypeUpdates,
                    scope,
                    name,
                    declaration
            );
            return matchPatternType != null
                    ? matchPatternType
                    : findLocalSlotTypeUpdate(localSlotTypeUpdates, scope, name, declaration);
        }

        private void mergeFrom(@NotNull OverlayFacts incoming) {
            mergeSideTable(symbolBindings, incoming.symbolBindings, "symbolBindings", FrontendAnalysisData::sameBinding);
            mergeSideTable(resolvedMembers, incoming.resolvedMembers, "resolvedMembers", FrontendAnalysisData::sameResolvedMember);
            mergeSideTable(
                    chainResolvedCalls,
                    incoming.chainResolvedCalls,
                    "resolvedCalls",
                    FrontendAnalysisData::sameResolvedCall
            );
            mergeSideTable(
                    exprResolvedCalls,
                    incoming.exprResolvedCalls,
                    "resolvedCalls",
                    FrontendAnalysisData::sameResolvedCall
            );
            mergeSideTable(expressionTypes, incoming.expressionTypes, "expressionTypes", FrontendAnalysisData::sameExpressionType);
            mergeSideTable(slotTypes, incoming.slotTypes, "slotTypes", FrontendAnalysisData::sameType);
            mergeSideTable(
                    forIterationPlans,
                    incoming.forIterationPlans,
                    "forIterationPlans",
                    FrontendForIterationPlan::samePlan
            );
            mergeSideTable(
                    matchPlans,
                    incoming.matchPlans,
                    "matchPlans",
                    FrontendMatchPlan::samePlan
            );
            mergeSideTable(
                    typeTestTargets,
                    incoming.typeTestTargets,
                    "typeTestTargets",
                    FrontendTypeTestTarget::sameTarget
            );
            mergeSideTable(
                    containerLiteralPlans,
                    incoming.containerLiteralPlans,
                    "containerLiteralPlans",
                    FrontendContainerLiteralPlan::samePlan
            );
            mergeSideTable(lambdaPlans, incoming.lambdaPlans, "lambdaPlans", FrontendLambdaPlan::samePlan);
            localSlotTypeUpdates.addAll(incoming.localSlotTypeUpdates);
            forIterationSlotTypeUpdates.addAll(incoming.forIterationSlotTypeUpdates);
            matchPatternSlotTypeUpdates.addAll(incoming.matchPatternSlotTypeUpdates);
        }

        private void checkNoCompilerOnlyLeaks() {
            FrontendPublishedFactTypeGuard.checkSymbolBindings(symbolBindings);
            FrontendPublishedFactTypeGuard.checkResolvedMembers(resolvedMembers);
            FrontendPublishedFactTypeGuard.checkResolvedCalls(chainResolvedCalls);
            FrontendPublishedFactTypeGuard.checkResolvedCalls(exprResolvedCalls);
            FrontendPublishedFactTypeGuard.checkExpressionTypes(expressionTypes);
            FrontendPublishedFactTypeGuard.checkSlotTypes(slotTypes);
            FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(localSlotTypeUpdates);
            FrontendPublishedFactTypeGuard.checkForIterationPlans(forIterationPlans);
            FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(forIterationSlotTypeUpdates);
            FrontendPublishedFactTypeGuard.checkMatchPlans(matchPlans);
            FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(matchPatternSlotTypeUpdates);
            FrontendPublishedFactTypeGuard.checkTypeTestTargets(typeTestTargets);
            FrontendPublishedFactTypeGuard.checkContainerLiteralPlans(containerLiteralPlans);
            FrontendPublishedFactTypeGuard.checkLambdaPlans(lambdaPlans);
        }

        private @NotNull List<FrontendOwnerPatch> toOwnerPatches() {
            var patches = new ArrayList<FrontendOwnerPatch>();
            if (!symbolBindings.isEmpty()) {
                patches.add(new FrontendTopBindingPatch(symbolBindings));
            }
            if (!localSlotTypeUpdates.isEmpty()) {
                patches.add(new FrontendLocalTypeStabilizationPatch(localSlotTypeUpdates));
            }
            if (!resolvedMembers.isEmpty() || !chainResolvedCalls.isEmpty()) {
                patches.add(new FrontendChainBindingPatch(resolvedMembers, chainResolvedCalls));
            }
            if (!expressionTypes.isEmpty()
                    || !exprResolvedCalls.isEmpty()
                    || !typeTestTargets.isEmpty()
                    || !containerLiteralPlans.isEmpty()) {
                patches.add(new FrontendExprTypePatch(
                        expressionTypes,
                        exprResolvedCalls,
                        typeTestTargets,
                        containerLiteralPlans
                ));
            }
            if (!forIterationPlans.isEmpty() || !forIterationSlotTypeUpdates.isEmpty()) {
                patches.add(new FrontendForIterationResolutionPatch(forIterationPlans, forIterationSlotTypeUpdates));
            }
            if (!matchPlans.isEmpty() || !matchPatternSlotTypeUpdates.isEmpty()) {
                patches.add(new FrontendMatchResolutionPatch(matchPlans, matchPatternSlotTypeUpdates));
            }
            if (!slotTypes.isEmpty()) {
                patches.add(new FrontendVarTypePostPatch(slotTypes));
            }
            if (!lambdaPlans.isEmpty()) {
                patches.add(new FrontendLambdaResolutionPatch(lambdaPlans));
            }
            return patches;
        }

        private boolean hasFacts() {
            return !symbolBindings.isEmpty()
                    || !resolvedMembers.isEmpty()
                    || !chainResolvedCalls.isEmpty()
                    || !exprResolvedCalls.isEmpty()
                    || !expressionTypes.isEmpty()
                    || !slotTypes.isEmpty()
                    || !localSlotTypeUpdates.isEmpty()
                    || !forIterationPlans.isEmpty()
                    || !forIterationSlotTypeUpdates.isEmpty()
                    || !matchPlans.isEmpty()
                    || !matchPatternSlotTypeUpdates.isEmpty()
                    || !typeTestTargets.isEmpty()
                    || !containerLiteralPlans.isEmpty()
                    || !lambdaPlans.isEmpty();
        }

        private void clear() {
            symbolBindings.clear();
            resolvedMembers.clear();
            chainResolvedCalls.clear();
            exprResolvedCalls.clear();
            expressionTypes.clear();
            slotTypes.clear();
            localSlotTypeUpdates.clear();
            forIterationPlans.clear();
            forIterationSlotTypeUpdates.clear();
            matchPlans.clear();
            matchPatternSlotTypeUpdates.clear();
            typeTestTargets.clear();
            containerLiteralPlans.clear();
            lambdaPlans.clear();
        }

        private static <V> void mergeSideTable(
                @NotNull FrontendAstSideTable<V> target,
                @NotNull FrontendAstSideTable<V> source,
                @NotNull String fieldName,
                @NotNull SameValueChecker<V> sameValueChecker
        ) {
            for (var entry : source.entrySet()) {
                checkConflict(fieldName, entry.getKey(), target.get(entry.getKey()), entry.getValue(), sameValueChecker);
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
