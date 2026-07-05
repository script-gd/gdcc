package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Window-local scratch publication surface layered over stable analysis data.
///
/// Reads always prefer scratch facts and then fall back to the stable side tables captured from
/// `FrontendAnalysisData`. Writes never mutate the stable tables directly; callers must explicitly
/// convert the scratch state into a `FrontendAnalysisPatch` or discard it.
public final class FrontendWindowPublicationSurface {
    private final @NotNull WindowSideTableView<FrontendBinding> symbolBindings;
    private final @NotNull WindowSideTableView<FrontendResolvedMember> resolvedMembers;
    private final @NotNull WindowSideTableView<FrontendResolvedCall> resolvedCalls;
    private final @NotNull WindowSideTableView<FrontendExpressionType> expressionTypes;
    private final @NotNull WindowSideTableView<GdType> slotTypes;
    private final @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates = new ArrayList<>();
    private boolean open = true;

    public FrontendWindowPublicationSurface(@NotNull FrontendAnalysisData stableData) {
        var checkedStableData = Objects.requireNonNull(stableData, "stableData must not be null");
        symbolBindings = new WindowSideTableView<>(
                checkedStableData.symbolBindings(),
                FrontendAnalysisData::sameBinding,
                "symbolBindings",
                null
        );
        resolvedMembers = new WindowSideTableView<>(
                checkedStableData.resolvedMembers(),
                FrontendAnalysisData::sameResolvedMember,
                "resolvedMembers",
                null
        );
        resolvedCalls = new WindowSideTableView<>(
                checkedStableData.resolvedCalls(),
                FrontendAnalysisData::sameResolvedCall,
                "resolvedCalls",
                null
        );
        expressionTypes = new WindowSideTableView<>(
                checkedStableData.expressionTypes(),
                FrontendAnalysisData::sameExpressionType,
                "expressionTypes",
                value -> FrontendAnalysisData.checkNoCompilerOnlyLeak(
                        value.publishedType(),
                        "expressionTypes() published type"
                )
        );
        slotTypes = new WindowSideTableView<>(
                checkedStableData.slotTypes(),
                FrontendAnalysisData::sameType,
                "slotTypes",
                value -> FrontendAnalysisData.checkNoCompilerOnlyLeak(value, "slotTypes() value")
        );
    }

    public @NotNull WindowSideTableView<FrontendBinding> symbolBindings() {
        return symbolBindings;
    }

    public @NotNull WindowSideTableView<FrontendResolvedMember> resolvedMembers() {
        return resolvedMembers;
    }

    public @NotNull WindowSideTableView<FrontendResolvedCall> resolvedCalls() {
        return resolvedCalls;
    }

    public @NotNull WindowSideTableView<FrontendExpressionType> expressionTypes() {
        return expressionTypes;
    }

    public @NotNull WindowSideTableView<GdType> slotTypes() {
        return slotTypes;
    }

    /// Collects one local-slot rewrite without mutating the underlying scope until commit time.
    public void addLocalSlotTypeUpdate(@NotNull FrontendLocalSlotTypeUpdate update) {
        requireOpen();
        var checkedUpdate = Objects.requireNonNull(update, "update must not be null");
        FrontendAnalysisData.checkNoVoidLocalSlotType(checkedUpdate.type(), checkedUpdate.name());
        FrontendAnalysisData.checkNoCompilerOnlyLeak(
                checkedUpdate.type(),
                "local slot update for '" + checkedUpdate.name() + "'"
        );
        localSlotTypeUpdates.add(checkedUpdate);
    }

    public @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates() {
        return List.copyOf(localSlotTypeUpdates);
    }

    /// Snapshots only scratch-owned facts into a patch. Stable fallback entries are intentionally
    /// excluded so commit remains an explicit delta over the pre-window publication surface.
    public @NotNull FrontendAnalysisPatch toPatch(@NotNull FrontendSemanticStage stage) {
        requireOpen();
        var checkedStage = Objects.requireNonNull(stage, "stage must not be null");
        checkLocalSlotUpdateStage(checkedStage);
        return new FrontendAnalysisPatch(
                checkedStage,
                symbolBindings.copyScratch(),
                resolvedMembers.copyScratch(),
                resolvedCalls.copyScratch(),
                expressionTypes.copyScratch(),
                slotTypes.copyScratch(),
                List.copyOf(localSlotTypeUpdates)
        );
    }

    /// Snapshots the scratch facts into a patch and then closes the surface.
    public @NotNull FrontendAnalysisPatch drainPatch(@NotNull FrontendSemanticStage stage) {
        var patch = toPatch(stage);
        clearScratch();
        open = false;
        return patch;
    }

    /// Drops every scratch fact so an unsupported or failed window cannot affect stable data.
    public void discard() {
        if (!open) {
            return;
        }
        clearScratch();
        open = false;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("window publication surface is closed");
        }
    }

    private void checkLocalSlotUpdateStage(@NotNull FrontendSemanticStage stage) {
        if (!localSlotTypeUpdates.isEmpty() && stage != FrontendSemanticStage.LOCAL_TYPE_STABILIZATION) {
            throw FrontendAnalysisData.patchFailure(
                    "Only LOCAL_TYPE_STABILIZATION patches may publish local slot type updates, but got "
                            + stage
            );
        }
    }

    private void clearScratch() {
        symbolBindings.clearScratch();
        resolvedMembers.clearScratch();
        resolvedCalls.clearScratch();
        expressionTypes.clearScratch();
        slotTypes.clearScratch();
        localSlotTypeUpdates.clear();
    }

    /// Per-table effective view used by one semantic window.
    public final class WindowSideTableView<V> {
        private final @NotNull FrontendAstSideTable<V> stable;
        private final @NotNull FrontendAstSideTable<V> scratch = new FrontendAstSideTable<>();
        private final @NotNull SameValueChecker<V> sameValueChecker;
        private final @NotNull String fieldName;
        private final @Nullable ValueGuard<V> valueGuard;

        private WindowSideTableView(
                @NotNull FrontendAstSideTable<V> stable,
                @NotNull SameValueChecker<V> sameValueChecker,
                @NotNull String fieldName,
                @Nullable ValueGuard<V> valueGuard
        ) {
            this.stable = Objects.requireNonNull(stable, "stable must not be null");
            this.sameValueChecker = Objects.requireNonNull(sameValueChecker, "sameValueChecker must not be null");
            this.fieldName = Objects.requireNonNull(fieldName, "fieldName must not be null");
            this.valueGuard = valueGuard;
        }

        public @Nullable V get(@NotNull Node astNode) {
            var checkedNode = Objects.requireNonNull(astNode, "astNode must not be null");
            var scratchValue = scratch.get(checkedNode);
            return scratchValue != null ? scratchValue : stable.get(checkedNode);
        }

        public @Nullable V getScratch(@NotNull Node astNode) {
            return scratch.get(Objects.requireNonNull(astNode, "astNode must not be null"));
        }

        public @Nullable V getStable(@NotNull Node astNode) {
            return stable.get(Objects.requireNonNull(astNode, "astNode must not be null"));
        }

        public boolean containsKey(@NotNull Node astNode) {
            var checkedNode = Objects.requireNonNull(astNode, "astNode must not be null");
            return scratch.containsKey(checkedNode) || stable.containsKey(checkedNode);
        }

        /// Records one scratch fact. Same-key writes are idempotent only when the logical value is
        /// unchanged; otherwise the write fails immediately instead of silently shadowing stable data.
        public void put(@NotNull Node astNode, @NotNull V value) {
            requireOpen();
            var checkedNode = Objects.requireNonNull(astNode, "astNode must not be null");
            var checkedValue = Objects.requireNonNull(value, "value must not be null");
            if (valueGuard != null) {
                valueGuard.check(checkedValue);
            }
            checkConflictingWrite(stable.get(checkedNode), checkedValue, checkedNode);
            checkConflictingWrite(scratch.get(checkedNode), checkedValue, checkedNode);
            scratch.put(checkedNode, checkedValue);
        }

        private void checkConflictingWrite(
                @Nullable V existingValue,
                @NotNull V newValue,
                @NotNull Node astNode
        ) {
            if (existingValue == null || sameValueChecker.sameValue(existingValue, newValue)) {
                return;
            }
            throw FrontendAnalysisData.patchFailure(
                    fieldName + " scratch write conflicted on " + FrontendAnalysisData.describeNode(astNode)
            );
        }

        private @NotNull FrontendAstSideTable<V> copyScratch() {
            var copy = new FrontendAstSideTable<V>();
            copy.putAll(scratch);
            return copy;
        }

        private void clearScratch() {
            scratch.clear();
        }
    }

    @FunctionalInterface
    private interface SameValueChecker<V> {
        boolean sameValue(@NotNull V first, @NotNull V second);
    }

    @FunctionalInterface
    private interface ValueGuard<V> {
        void check(@NotNull V value);
    }
}
