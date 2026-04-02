package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

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
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
    private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
    private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
    private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;
    private final @NotNull FrontendAstSideTable<GdType> slotTypes;

    private FrontendAnalysisData(
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
            @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
            @NotNull FrontendAstSideTable<GdType> slotTypes
    ) {
        this.annotationsByAst = Objects.requireNonNull(annotationsByAst, "annotationsByAst must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
        this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
        this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
        this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
        this.slotTypes = Objects.requireNonNull(
                slotTypes,
                "slotTypes must not be null"
        );
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
                new FrontendAstSideTable<>()
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
        replaceSideTableContents(this.symbolBindings, symbolBindings, "symbolBindings");
    }

    public void updateExpressionTypes(@NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes) {
        replaceSideTableContents(this.expressionTypes, expressionTypes, "expressionTypes");
    }

    public void updateResolvedMembers(@NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers) {
        replaceSideTableContents(this.resolvedMembers, resolvedMembers, "resolvedMembers");
    }

    public void updateResolvedCalls(@NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls) {
        replaceSideTableContents(this.resolvedCalls, resolvedCalls, "resolvedCalls");
    }

    public void updateSlotTypes(@NotNull FrontendAstSideTable<GdType> slotTypes) {
        replaceSideTableContents(
                this.slotTypes,
                slotTypes,
                "slotTypes"
        );
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

    public @NotNull FrontendAstSideTable<Scope> scopesByAst() {
        return scopesByAst;
    }

    public @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings() {
        return symbolBindings;
    }

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

    private <T> @NotNull T requirePublished(@Nullable T value, @NotNull String fieldName) {
        if (value == null) {
            throw new IllegalStateException(fieldName + " has not been published yet");
        }
        return value;
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
}
