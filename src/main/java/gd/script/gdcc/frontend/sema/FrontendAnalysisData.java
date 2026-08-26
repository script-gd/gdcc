package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.sema.patch.FrontendOwnerPatch;
import gd.script.gdcc.frontend.sema.patch.FrontendPublishedFactTypeGuard;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Unified frontend analysis data container shared across semantic phases.
///
/// The object is created early with a complete set of mutable side tables, then later phases
/// update each published field through explicit `updateXxx(...)` methods once those results
/// exist. This keeps downstream helpers passing one semantic data object instead of threading
/// individual side tables through every call while still making each mutation site obvious.
public final class FrontendAnalysisData {
    private @Nullable FrontendModuleSkeleton moduleSkeleton;
    private @Nullable DiagnosticSnapshot diagnostics;
    private final @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst;
    /// Skeleton/header phases mark roots here when later semantic traversal must skip the whole
    /// subtree. Scope analysis then withholds `scopesByAst()` publication for those roots and their
    /// descendants so downstream analyzers can reuse the existing skipped-subtree contract.
    private final @NotNull FrontendAstSideTable<Boolean> skippedSubtreeRoots;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
    /// Published expression-typing facts consumed by compile-check, lowering, and debug tooling.
    /// The key space is intentionally wider than `Expression`: publishers may anchor facts on
    /// `Expression`, `AttributePropertyStep`, `AttributeCallStep`, or `AttributeSubscriptStep`.
    /// Consumers must not assume `entrySet()` is expression-only.
    private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
    private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
    private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;
    private final @NotNull FrontendAstSideTable<GdType> slotTypes;
    /// Published for-in iteration plans keyed by the owning `ForStatement`. Consumed by type-check,
    /// compile gate and CFG builder as the single route/element-type truth; never carries lowering
    /// protocol or compiler-only types.
    private final @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans;
    /// Published match plans keyed by the owning `MatchStatement`. Consumed by type-check,
    /// compile gate and CFG builder as the single route/binding truth; never carries lowering
    /// protocol or compiler-only types.
    private final @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans;
    /// Published type-test RHS targets keyed by the owning `TypeTestExpression`.
    /// Carries either a resolved `GdType` or an unresolved object class name for runtime checking.
    private final @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets;
    /// Published container-literal construction plans keyed by `ArrayExpression` / `DictionaryExpression`.
    /// Freezes element boundary decisions and duplicate-key issues for type-check / CFG / lowering.
    private final @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> containerLiteralPlans;
    /// Published lambda identity/capture plans keyed by `LambdaExpression`. Inventory never
    /// publishes placeholder plans; the first entry is the complete `LAMBDA_RESOLUTION` payload.
    private final @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans;
    /// Monotonic identity set of GDCC callables proven to be coroutines, i.e. directly containing a
    /// real await (signal/dynamic route) or awaiting a call to another coroutine. Keyed by the
    /// callable's skeleton `LirFunctionDef` — not by AST node — because every downstream consumer
    /// (await coroutine fixed-point, compile-gate position checks, lowering skeleton pass) only ever
    /// holds the `FunctionDef` from a resolved call or the lowering shell. Entries are added during
    /// `EXPR_TYPE` (signal/dynamic awaits) and by the post-suite await coroutine fixed-point pass
    /// (await-of-coroutine-call). The set is only read after suite resolution completes, so the
    /// per-owner export-batch discipline does not apply to it.
    private final @NotNull Set<LirFunctionDef> coroutineFunctions;
    /// Monotonic identity set of lambda owners (`LambdaExpression`) whose bodies directly contain
    /// a real await or await a call to another coroutine (`frontend_await_implementation.md` §8).
    /// Keyed by AST identity because the synthetic `_lambda_<n>` shell does not exist during sema;
    /// the lowering function-preparation pass bridges each marked owner to its freshly synthesized
    /// shell (`setCoroutine(true)` + `markCoroutineFunction(shell)` — both are required: the former
    /// is the LIR/backend fact, the latter is the lowering membership source).
    private final @NotNull Set<LambdaExpression> coroutineLambdaOwners;
    /// Transient working list of await expressions whose operand is an exact call: `EXPR_TYPE`
    /// publishes a provisional callee-return result, but later owners can still determine that the
    /// callee is a coroutine. The post-suite fixed point consumes and clears these entries, refines
    /// non-coroutine Signal-call results, preserves Variant dynamic results, and marks only other
    /// hard returns as redundant. Never itself a published fact for lowering.
    private final @NotNull List<FrontendAwaitCallPending> awaitCallPendings;

    private FrontendAnalysisData(
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst,
            @NotNull FrontendAstSideTable<Boolean> skippedSubtreeRoots,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
            @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
            @NotNull FrontendAstSideTable<GdType> slotTypes,
            @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans,
            @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans,
            @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets,
            @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> containerLiteralPlans,
            @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans,
            @NotNull Set<LirFunctionDef> coroutineFunctions,
            @NotNull Set<LambdaExpression> coroutineLambdaOwners,
            @NotNull List<FrontendAwaitCallPending> awaitCallPendings
    ) {
        this.annotationsByAst = Objects.requireNonNull(annotationsByAst, "annotationsByAst must not be null");
        this.skippedSubtreeRoots = Objects.requireNonNull(
                skippedSubtreeRoots,
                "skippedSubtreeRoots must not be null"
        );
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
        this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
        this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
        this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
        this.slotTypes = Objects.requireNonNull(
                slotTypes,
                "slotTypes must not be null"
        );
        this.forIterationPlans = Objects.requireNonNull(
                forIterationPlans,
                "forIterationPlans must not be null"
        );
        this.matchPlans = Objects.requireNonNull(matchPlans, "matchPlans must not be null");
        this.typeTestTargets = Objects.requireNonNull(typeTestTargets, "typeTestTargets must not be null");
        this.containerLiteralPlans = Objects.requireNonNull(
                containerLiteralPlans,
                "containerLiteralPlans must not be null"
        );
        this.lambdaPlans = Objects.requireNonNull(lambdaPlans, "lambdaPlans must not be null");
        this.coroutineFunctions = Objects.requireNonNull(coroutineFunctions, "coroutineFunctions must not be null");
        this.coroutineLambdaOwners = Objects.requireNonNull(
                coroutineLambdaOwners,
                "coroutineLambdaOwners must not be null"
        );
        this.awaitCallPendings = Objects.requireNonNull(awaitCallPendings, "awaitCallPendings must not be null");
    }

    /// Creates an empty analysis data carrier with the full side-table topology already present.
    public static @NotNull FrontendAnalysisData bootstrap() {
        return new FrontendAnalysisData(
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                Collections.newSetFromMap(new IdentityHashMap<>()),
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new ArrayList<>()
        );
    }

    public void updateModuleSkeleton(@NotNull FrontendModuleSkeleton moduleSkeleton) {
        this.moduleSkeleton = Objects.requireNonNull(moduleSkeleton, "moduleSkeleton must not be null");
    }

    public void updateDiagnostics(@NotNull DiagnosticSnapshot diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    public void updateAnnotationsByAst(@NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst) {
        replaceSideTableContents(this.annotationsByAst, annotationsByAst, "annotationsByAst");
    }

    public void updateScopesByAst(@NotNull FrontendAstSideTable<Scope> scopesByAst) {
        replaceSideTableContents(this.scopesByAst, scopesByAst, "scopesByAst");
    }

    public void updateSymbolBindings(@NotNull FrontendAstSideTable<FrontendBinding> symbolBindings) {
        FrontendPublishedFactTypeGuard.checkSymbolBindings(symbolBindings);
        replaceSideTableContents(this.symbolBindings, symbolBindings, "symbolBindings");
    }

    /// Replaces the published expression-fact snapshot in place while preserving the stable table
    /// reference. Callers may publish both expression-root facts and attribute-step facts here.
    public void updateExpressionTypes(@NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes) {
        FrontendPublishedFactTypeGuard.checkExpressionTypes(expressionTypes);
        replaceSideTableContents(this.expressionTypes, expressionTypes, "expressionTypes");
    }

    public void updateResolvedMembers(@NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers) {
        FrontendPublishedFactTypeGuard.checkResolvedMembers(resolvedMembers);
        replaceSideTableContents(this.resolvedMembers, resolvedMembers, "resolvedMembers");
    }

    public void updateResolvedCalls(@NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls) {
        FrontendPublishedFactTypeGuard.checkResolvedCalls(resolvedCalls);
        replaceSideTableContents(this.resolvedCalls, resolvedCalls, "resolvedCalls");
    }

    public void updateSlotTypes(@NotNull FrontendAstSideTable<GdType> slotTypes) {
        FrontendPublishedFactTypeGuard.checkSlotTypes(slotTypes);
        replaceSideTableContents(
                this.slotTypes,
                slotTypes,
                "slotTypes"
        );
    }

    public void updateForIterationPlans(@NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans) {
        FrontendPublishedFactTypeGuard.checkForIterationPlans(forIterationPlans);
        replaceSideTableContents(this.forIterationPlans, forIterationPlans, "forIterationPlans");
    }

    public void updateMatchPlans(@NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans) {
        FrontendPublishedFactTypeGuard.checkMatchPlans(matchPlans);
        replaceSideTableContents(this.matchPlans, matchPlans, "matchPlans");
    }

    public void updateTypeTestTargets(@NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets) {
        FrontendPublishedFactTypeGuard.checkTypeTestTargets(typeTestTargets);
        replaceSideTableContents(this.typeTestTargets, typeTestTargets, "typeTestTargets");
    }

    public void updateContainerLiteralPlans(
            @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> containerLiteralPlans
    ) {
        FrontendPublishedFactTypeGuard.checkContainerLiteralPlans(containerLiteralPlans);
        replaceSideTableContents(this.containerLiteralPlans, containerLiteralPlans, "containerLiteralPlans");
    }

    public void updateLambdaPlans(@NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans) {
        FrontendPublishedFactTypeGuard.checkLambdaPlans(lambdaPlans);
        replaceSideTableContents(this.lambdaPlans, lambdaPlans, "lambdaPlans");
    }

    /// Applies one single-owner patch without replacing any stable side-table reference.
    ///
    /// Conflict checks and local-slot validation are scoped to this patch. Repeated calls, including
    /// calls from `FrontendPatchTransaction`, do not form an atomic unit and do not roll back earlier patches.
    public void applyPatch(@NotNull FrontendOwnerPatch patch) {
        var checkedPatch = Objects.requireNonNull(patch, "patch must not be null");
        FrontendPublishedFactTypeGuard.checkOwnerPatch(checkedPatch);
        applyPatchFields(
                checkedPatch.stage(),
                checkedPatch.symbolBindings(),
                checkedPatch.resolvedMembers(),
                checkedPatch.resolvedCalls(),
                checkedPatch.expressionTypes(),
                checkedPatch.slotTypes(),
                checkedPatch.forIterationPlans(),
                checkedPatch.matchPlans(),
                checkedPatch.typeTestTargets(),
                checkedPatch.containerLiteralPlans(),
                checkedPatch.lambdaPlans(),
                checkedPatch.localSlotTypeUpdates()
        );
    }

    private void applyPatchFields(
            @NotNull FrontendSemanticStage stage,
            @NotNull FrontendAstSideTable<FrontendBinding> patchSymbolBindings,
            @NotNull FrontendAstSideTable<FrontendResolvedMember> patchResolvedMembers,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> patchResolvedCalls,
            @NotNull FrontendAstSideTable<FrontendExpressionType> patchExpressionTypes,
            @NotNull FrontendAstSideTable<GdType> patchSlotTypes,
            @NotNull FrontendAstSideTable<FrontendForIterationPlan> patchForIterationPlans,
            @NotNull FrontendAstSideTable<FrontendMatchPlan> patchMatchPlans,
            @NotNull FrontendAstSideTable<FrontendTypeTestTarget> patchTypeTestTargets,
            @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> patchContainerLiteralPlans,
            @NotNull FrontendAstSideTable<FrontendLambdaPlan> patchLambdaPlans,
            @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
    ) {
        var validatedLocalSlotUpdates = validateLocalSlotTypeUpdates(stage, localSlotTypeUpdates);
        checkPatchConflicts(symbolBindings, patchSymbolBindings, "symbolBindings", FrontendAnalysisData::sameBinding);
        checkPatchConflicts(
                resolvedMembers,
                patchResolvedMembers,
                "resolvedMembers",
                FrontendAnalysisData::sameResolvedMember
        );
        checkPatchConflicts(
                resolvedCalls,
                patchResolvedCalls,
                "resolvedCalls",
                FrontendAnalysisData::sameResolvedCall
        );
        checkPatchConflicts(
                expressionTypes,
                patchExpressionTypes,
                "expressionTypes",
                FrontendAnalysisData::sameExpressionType
        );
        checkPatchConflicts(slotTypes, patchSlotTypes, "slotTypes", FrontendAnalysisData::sameType);
        checkPatchConflicts(
                forIterationPlans,
                patchForIterationPlans,
                "forIterationPlans",
                FrontendForIterationPlan::samePlan
        );
        checkPatchConflicts(
                matchPlans,
                patchMatchPlans,
                "matchPlans",
                FrontendMatchPlan::samePlan
        );
        checkPatchConflicts(
                typeTestTargets,
                patchTypeTestTargets,
                "typeTestTargets",
                FrontendTypeTestTarget::sameTarget
        );
        checkPatchConflicts(
                containerLiteralPlans,
                patchContainerLiteralPlans,
                "containerLiteralPlans",
                FrontendContainerLiteralPlan::samePlan
        );
        checkPatchConflicts(
                lambdaPlans,
                patchLambdaPlans,
                "lambdaPlans",
                FrontendLambdaPlan::samePlan
        );

        mergeSideTable(symbolBindings, patchSymbolBindings);
        mergeSideTable(resolvedMembers, patchResolvedMembers);
        mergeSideTable(resolvedCalls, patchResolvedCalls);
        mergeSideTable(expressionTypes, patchExpressionTypes);
        mergeSideTable(slotTypes, patchSlotTypes);
        mergeSideTable(forIterationPlans, patchForIterationPlans);
        mergeSideTable(matchPlans, patchMatchPlans);
        mergeSideTable(typeTestTargets, patchTypeTestTargets);
        mergeSideTable(containerLiteralPlans, patchContainerLiteralPlans);
        mergeSideTable(lambdaPlans, patchLambdaPlans);
        for (var validatedUpdate : validatedLocalSlotUpdates) {
            applyLocalSlotTypeUpdate(validatedUpdate);
        }
    }

    public @NotNull FrontendModuleSkeleton moduleSkeleton() {
        return requirePublished(moduleSkeleton, "moduleSkeleton");
    }

    public @NotNull DiagnosticSnapshot diagnostics() {
        return requirePublished(diagnostics, "diagnostics");
    }

    public @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst() {
        return annotationsByAst;
    }

    public @NotNull FrontendAstSideTable<Boolean> skippedSubtreeRoots() {
        return skippedSubtreeRoots;
    }

    public @NotNull FrontendAstSideTable<Scope> scopesByAst() {
        return scopesByAst;
    }

    public @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings() {
        return symbolBindings;
    }

    /// Returns the stable published expression-fact table.
    /// Key space note: this table is not `Expression`-only; attribute property/call/subscript steps
    /// are also valid keys when their published facts need a more precise downstream anchor.
    public @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes() {
        return expressionTypes;
    }

    public @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers() {
        return resolvedMembers;
    }

    public @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls() {
        return resolvedCalls;
    }

    public @NotNull FrontendAstSideTable<GdType> slotTypes() {
        return slotTypes;
    }

    public @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans() {
        return forIterationPlans;
    }

    public @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans() {
        return matchPlans;
    }

    public @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets() {
        return typeTestTargets;
    }

    public @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> containerLiteralPlans() {
        return containerLiteralPlans;
    }

    public @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans() {
        return lambdaPlans;
    }

    /// Read-only view of the coroutine callable set; mutation goes through `markCoroutineFunction`.
    public @NotNull Set<LirFunctionDef> coroutineFunctions() {
        return Collections.unmodifiableSet(coroutineFunctions);
    }

    /// Marks a callable as a coroutine. Idempotent and monotonic; returns true when the marking was
    /// newly added so the await fixed-point pass can detect progress.
    public boolean markCoroutineFunction(@NotNull LirFunctionDef functionDef) {
        return coroutineFunctions.add(Objects.requireNonNull(functionDef, "functionDef must not be null"));
    }

    /// Read-only view of the lambda coroutine owner set; mutation goes through
    /// `markCoroutineLambdaOwner` / `markCoroutineOwner`. Consumed by the lowering
    /// function-preparation pass when it synthesizes each lambda shell.
    public @NotNull Set<LambdaExpression> coroutineLambdaOwners() {
        return Collections.unmodifiableSet(coroutineLambdaOwners);
    }

    /// Marks a lambda owner as a coroutine by AST identity. Idempotent and monotonic; returns
    /// true when the marking was newly added so the await fixed-point pass can detect progress.
    public boolean markCoroutineLambdaOwner(@NotNull LambdaExpression lambdaExpression) {
        return coroutineLambdaOwners.add(
                Objects.requireNonNull(lambdaExpression, "lambdaExpression must not be null")
        );
    }

    /// Marks an await's enclosing callable owner as a coroutine, dispatching on the owner shape:
    /// named/constructor skeletons join `coroutineFunctions` directly; lambda owners join
    /// `coroutineLambdaOwners` and are bridged to their shell during lowering preparation.
    public boolean markCoroutineOwner(@NotNull FrontendAwaitCoroutineOwner owner) {
        return switch (Objects.requireNonNull(owner, "owner must not be null")) {
            case FrontendAwaitCoroutineOwner.NamedFunction(var function) -> markCoroutineFunction(function);
            case FrontendAwaitCoroutineOwner.Lambda(var lambda) -> markCoroutineLambdaOwner(lambda);
        };
    }

    /// Refines an exact-call await after the post-suite coroutine fixed point. This is required for
    /// a non-coroutine callee returning `Signal`: before the fixed point the result provisionally
    /// has the callee return type, while afterwards it becomes the signal's resume-value type.
    public void refineResolvedAwaitExpressionType(
            @NotNull AwaitExpression awaitExpression,
            @NotNull GdType refinedType
    ) {
        var checkedAwait = Objects.requireNonNull(awaitExpression, "awaitExpression must not be null");
        var checkedType = Objects.requireNonNull(refinedType, "refinedType must not be null");
        var current = expressionTypes.get(checkedAwait);
        if (current == null || current.status() != FrontendExpressionTypeStatus.RESOLVED) {
            throw new IllegalStateException(
                    "Await result refinement requires an existing RESOLVED expression fact"
            );
        }
        expressionTypes.put(checkedAwait, FrontendExpressionType.resolved(checkedType));
    }

    /// Working list consumed by the post-suite await coroutine pass; not a stable fact. Mutation
    /// goes through `addAwaitCallPending` / `drainAwaitCallPendings` only.
    public @NotNull List<FrontendAwaitCallPending> awaitCallPendings() {
        return Collections.unmodifiableList(awaitCallPendings);
    }

    /// Returns a snapshot of all recorded await-call pendings and clears the working list; used by
    /// the post-suite fixed-point pass as the single consumer.
    public @NotNull List<FrontendAwaitCallPending> drainAwaitCallPendings() {
        var drained = List.copyOf(awaitCallPendings);
        awaitCallPendings.clear();
        return drained;
    }

    public void addAwaitCallPending(@NotNull FrontendAwaitCallPending pending) {
        awaitCallPendings.add(Objects.requireNonNull(pending, "pending must not be null"));
    }

    /// Whether the published exact call at `callAnchor` targets a callable already marked as a
    /// coroutine. Pure read over two frozen tables (`resolvedCalls` + `coroutineFunctions`);
    /// non-exact routes and non-`LirFunctionDef` declaration sites can never be GDCC coroutines.
    public boolean isPublishedCoroutineCall(@NotNull Node callAnchor) {
        var publishedCall = resolvedCalls().get(Objects.requireNonNull(callAnchor, "callAnchor must not be null"));
        return publishedCall != null
                && publishedCall.status() == FrontendCallResolutionStatus.RESOLVED
                && publishedCall.declarationSite() instanceof LirFunctionDef calleeFunction
                && coroutineFunctions.contains(calleeFunction);
    }

    /// Refreshes published local bindings after a verified local-slot rewrite.
    ///
    /// This helper intentionally updates only the `resolvedValue` payload. The binding kind, source
    /// name, and declaration identity stay frozen from top binding so downstream phases keep seeing
    /// the same lexical choice while observing the narrowed slot type.
    public void refreshPublishedLocalBindingPayloads(
            @NotNull FrontendLocalSlotTypeUpdate slotTypeUpdate,
            @NotNull ScopeValue updatedValue
    ) {
        var checkedUpdate = Objects.requireNonNull(slotTypeUpdate, "slotTypeUpdate must not be null");
        var checkedUpdatedValue = Objects.requireNonNull(updatedValue, "updatedValue must not be null");
        if (checkedUpdatedValue.declaration() != checkedUpdate.declaration()) {
            throw patchFailure(
                    "local slot refresh resolved a different declaration for '"
                            + checkedUpdate.name()
                            + "'"
            );
        }
        if (!sameType(checkedUpdatedValue.type(), checkedUpdate.type())) {
            throw patchFailure(
                    "local slot refresh resolved an unexpected type for '"
                            + checkedUpdate.name()
                            + "': expected "
                            + checkedUpdate.type().getTypeName()
                            + ", got "
                            + checkedUpdatedValue.type().getTypeName()
            );
        }
        checkNoCompilerOnlyLeak(
                checkedUpdatedValue.type(),
                "symbolBindings local payload refresh for '" + checkedUpdate.name() + "'"
        );
        for (var entry : symbolBindings.entrySet()) {
            var binding = entry.getValue();
            var resolvedValue = binding.resolvedValue();
            if (resolvedValue == null || resolvedValue.declaration() != checkedUpdate.declaration()) {
                continue;
            }
            entry.setValue(binding.withResolvedValue(checkedUpdatedValue));
        }
    }

    private <T> @NotNull T requirePublished(@Nullable T value, @NotNull String fieldName) {
        if (value == null) {
            throw new IllegalStateException(fieldName + " has not been published yet");
        }
        return value;
    }

    private @NotNull List<ValidatedLocalSlotTypeUpdate> validateLocalSlotTypeUpdates(
            @NotNull FrontendSemanticStage stage,
            @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
    ) {
        if (localSlotTypeUpdates.isEmpty()) {
            return List.of();
        }
        // Three disjoint slot-update owners share the Variant->exact rewrite rules:
        // LOCAL_TYPE_STABILIZATION for ordinary `var :=` (VariableDeclaration identity),
        // FOR_ITERATION_RESOLUTION for the for-in iterator (owning ForStatement identity),
        // and MATCH_PATTERN_RESOLUTION for match binds (PatternBindingExpression identity).
        // Every other stage is rejected.
        if (stage != FrontendSemanticStage.LOCAL_TYPE_STABILIZATION
                && stage != FrontendSemanticStage.FOR_ITERATION_RESOLUTION
                && stage != FrontendSemanticStage.MATCH_PATTERN_RESOLUTION) {
            throw patchFailure(
                    "Only LOCAL_TYPE_STABILIZATION, FOR_ITERATION_RESOLUTION, or MATCH_PATTERN_RESOLUTION "
                            + "patches may publish local slot type updates, but got "
                            + stage
            );
        }
        var validatedUpdates = new ArrayList<ValidatedLocalSlotTypeUpdate>(localSlotTypeUpdates.size());
        for (var update : localSlotTypeUpdates) {
            checkSlotUpdateDeclarationDomain(stage, update);
            validatedUpdates.add(validateLocalSlotTypeUpdate(update, validatedUpdates));
        }
        return List.copyOf(validatedUpdates);
    }

    /// Enforces the two disjoint declaration-identity domains at the data boundary: ordinary local
    /// stabilization may only target `VariableDeclaration`, while for-iteration resolution may only
    /// target the owning `ForStatement` iterator. This keeps the two slot-update owners mutually
    /// exclusive even if a future producer misroutes an update.
    private static void checkSlotUpdateDeclarationDomain(
            @NotNull FrontendSemanticStage stage,
            @NotNull FrontendLocalSlotTypeUpdate update
    ) {
        var declaration = update.declaration();
        if (stage == FrontendSemanticStage.FOR_ITERATION_RESOLUTION && !(declaration instanceof ForStatement)) {
            throw patchFailure(
                    "FOR_ITERATION_RESOLUTION slot update must target a ForStatement iterator, but got "
                            + declaration.getClass().getSimpleName()
            );
        }
        if (stage == FrontendSemanticStage.LOCAL_TYPE_STABILIZATION && !(declaration instanceof VariableDeclaration)) {
            throw patchFailure(
                    "LOCAL_TYPE_STABILIZATION slot update must target a VariableDeclaration, but got "
                            + declaration.getClass().getSimpleName()
            );
        }
        if (stage == FrontendSemanticStage.MATCH_PATTERN_RESOLUTION
                && !(declaration instanceof PatternBindingExpression)) {
            throw patchFailure(
                    "MATCH_PATTERN_RESOLUTION slot update must target a PatternBindingExpression, but got "
                            + declaration.getClass().getSimpleName()
            );
        }
    }

    private @NotNull ValidatedLocalSlotTypeUpdate validateLocalSlotTypeUpdate(
            @NotNull FrontendLocalSlotTypeUpdate update,
            @NotNull List<ValidatedLocalSlotTypeUpdate> validatedUpdates
    ) {
        checkNoVoidLocalSlotType(update.type(), update.name());
        checkNoCompilerOnlyLeak(update.type(), "local slot update for '" + update.name() + "'");
        var existingValue = lookupPendingLocalSlotValue(update, validatedUpdates);
        if (existingValue == null) {
            existingValue = requireCurrentLocalSlotValue(update);
        }
        if (existingValue.declaration() != update.declaration()) {
            throw patchFailure(
                    "local slot update targeted a different declaration for '" + update.name() + "'"
            );
        }
        if (sameType(existingValue.type(), update.type())) {
            return new ValidatedLocalSlotTypeUpdate(update, null);
        }
        if (!(existingValue.type() instanceof GdVariantType)) {
            throw patchFailure(
                    "local slot update changed exact type for '"
                            + update.name()
                            + "' from "
                            + existingValue.type().getTypeName()
                            + " to "
                            + update.type().getTypeName()
            );
        }
        return new ValidatedLocalSlotTypeUpdate(
                update,
                new ScopeValue(
                        existingValue.name(),
                        update.type(),
                        existingValue.kind(),
                        existingValue.declaration(),
                        existingValue.constant(),
                        existingValue.writable(),
                        existingValue.staticMember()
                )
        );
    }

    private @Nullable ScopeValue lookupPendingLocalSlotValue(
            @NotNull FrontendLocalSlotTypeUpdate target,
            @NotNull List<ValidatedLocalSlotTypeUpdate> validatedUpdates
    ) {
        for (var i = validatedUpdates.size() - 1; i >= 0; i--) {
            var validatedUpdate = validatedUpdates.get(i);
            var update = validatedUpdate.update();
            if (update.scope() != target.scope()
                    || !update.name().equals(target.name())
                    || update.declaration() != target.declaration()) {
                continue;
            }
            return validatedUpdate.refreshedValue() != null
                    ? validatedUpdate.refreshedValue()
                    : requireCurrentLocalSlotValue(update);
        }
        return null;
    }

    private @NotNull ScopeValue requireCurrentLocalSlotValue(@NotNull FrontendLocalSlotTypeUpdate update) {
        var currentValue = update.scope().resolveValueHere(update.name());
        if (currentValue == null) {
            throw patchFailure("local slot update targeted a missing binding for '" + update.name() + "'");
        }
        if (currentValue.kind() != ScopeValueKind.LOCAL) {
            throw patchFailure("local slot update targeted a non-local binding for '" + update.name() + "'");
        }
        return currentValue;
    }

    private void applyLocalSlotTypeUpdate(@NotNull ValidatedLocalSlotTypeUpdate validatedUpdate) {
        var refreshedValue = validatedUpdate.refreshedValue();
        if (refreshedValue == null) {
            return;
        }
        var update = validatedUpdate.update();
        update.scope().resetLocalType(update.name(), update.declaration(), update.type());
        refreshPublishedLocalBindingPayloads(update, refreshedValue);
    }

    private <V> void checkPatchConflicts(
            @NotNull FrontendAstSideTable<V> stableTable,
            @NotNull FrontendAstSideTable<V> patchTable,
            @NotNull String fieldName,
            @NotNull SameValueChecker<V> sameValueChecker
    ) {
        for (var entry : patchTable.entrySet()) {
            var existingValue = stableTable.get(entry.getKey());
            if (existingValue == null || sameValueChecker.sameValue(existingValue, entry.getValue())) {
                continue;
            }
            throw patchFailure(
                    fieldName
                            + " patch conflicted on "
                            + describeNode(entry.getKey())
            );
        }
    }

    private static <V> void mergeSideTable(
            @NotNull FrontendAstSideTable<V> stableTable,
            @NotNull FrontendAstSideTable<V> patchTable
    ) {
        for (var entry : patchTable.entrySet()) {
            if (!stableTable.containsKey(entry.getKey())) {
                stableTable.put(entry.getKey(), entry.getValue());
            }
        }
    }

    static void checkNoCompilerOnlyLeak(@Nullable GdType type, @NotNull String fieldName) {
        FrontendPublishedFactTypeGuard.checkNoCompilerOnlyLeak(type, fieldName);
    }

    static void checkNoVoidLocalSlotType(@NotNull GdType type, @NotNull String localName) {
        if (type instanceof GdVoidType) {
            throw patchFailure("local slot update for '" + localName + "' must not publish void");
        }
    }

    static boolean sameBinding(@NotNull FrontendBinding first, @NotNull FrontendBinding second) {
        return first.kind() == second.kind()
                && first.symbolName().equals(second.symbolName())
                && first.declarationSite() == second.declarationSite()
                && first.valueAccessStatus() == second.valueAccessStatus()
                && sameScopeValue(first.resolvedValue(), second.resolvedValue());
    }

    static boolean sameResolvedMember(
            @NotNull FrontendResolvedMember first,
            @NotNull FrontendResolvedMember second
    ) {
        return first.bindingKind() == second.bindingKind()
                && first.status() == second.status()
                && first.receiverKind() == second.receiverKind()
                && first.ownerKind() == second.ownerKind()
                && first.declarationSite() == second.declarationSite()
                && first.memberName().equals(second.memberName())
                && sameType(first.receiverType(), second.receiverType())
                && sameType(first.resultType(), second.resultType())
                && Objects.equals(first.detailReason(), second.detailReason());
    }

    static boolean sameResolvedCall(@NotNull FrontendResolvedCall first, @NotNull FrontendResolvedCall second) {
        return first.callKind() == second.callKind()
                && first.status() == second.status()
                && first.receiverKind() == second.receiverKind()
                && first.ownerKind() == second.ownerKind()
                && first.declarationSite() == second.declarationSite()
                && first.callableName().equals(second.callableName())
                && sameType(first.receiverType(), second.receiverType())
                && sameType(first.returnType(), second.returnType())
                && sameTypeList(first.argumentTypes(), second.argumentTypes())
                && sameExactCallableBoundary(first.exactCallableBoundary(), second.exactCallableBoundary())
                && Objects.equals(first.detailReason(), second.detailReason());
    }

    static boolean sameExactCallableBoundary(
            @Nullable FrontendResolvedCall.ExactCallableBoundary first,
            @Nullable FrontendResolvedCall.ExactCallableBoundary second
    ) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.isVararg() == second.isVararg()
                && sameTypeList(first.fixedParameterTypes(), second.fixedParameterTypes());
    }

    static boolean sameExpressionType(
            @NotNull FrontendExpressionType first,
            @NotNull FrontendExpressionType second
    ) {
        return first.status() == second.status()
                && sameType(first.publishedType(), second.publishedType())
                && Objects.equals(first.detailReason(), second.detailReason());
    }

    static boolean sameScopeValue(@Nullable ScopeValue first, @Nullable ScopeValue second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.kind() == second.kind()
                && first.constant() == second.constant()
                && first.writable() == second.writable()
                && first.staticMember() == second.staticMember()
                && first.declaration() == second.declaration()
                && first.name().equals(second.name())
                && sameType(first.type(), second.type());
    }

    static boolean sameTypeList(@NotNull List<GdType> first, @NotNull List<GdType> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (var i = 0; i < first.size(); i++) {
            if (!sameType(first.get(i), second.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean sameType(@Nullable GdType first, @Nullable GdType second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first == second
                || (first.getClass() == second.getClass() && first.getTypeName().equals(second.getTypeName()));
    }

    static @NotNull String describeNode(@NotNull Node node) {
        return node.getClass().getSimpleName();
    }

    static @NotNull FrontendAnalysisPatchException patchFailure(@NotNull String message) {
        return new FrontendAnalysisPatchException(message);
    }

    private static <V> void replaceSideTableContents(
            @NotNull FrontendAstSideTable<V> target,
            @NotNull FrontendAstSideTable<? extends V> source,
            @NotNull String fieldName
    ) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        if (target == Objects.requireNonNull(source, fieldName + " must not be null")) {
            return;
        }
        target.clear();
        target.putAll(source);
    }

    @FunctionalInterface
    private interface SameValueChecker<V> {
        boolean sameValue(@NotNull V first, @NotNull V second);
    }

    private record ValidatedLocalSlotTypeUpdate(
            @NotNull FrontendLocalSlotTypeUpdate update,
            @Nullable ScopeValue refreshedValue
    ) {
        private ValidatedLocalSlotTypeUpdate {
            Objects.requireNonNull(update, "update must not be null");
        }
    }
}
