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
/// The object is created early with a complete set of mutable side tables, then later phase
/// boundaries publish the stable `moduleSkeleton` and diagnostic snapshot once those results
/// exist. This keeps downstream helpers passing one semantic data object instead of threading
/// individual side tables through every call.
public final class FrontendAnalysisData {
    private @Nullable FrontendModuleSkeleton moduleSkeleton;
    private @Nullable DiagnosticSnapshot diagnostics;
    private final @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
    private final @NotNull FrontendAstSideTable<GdType> expressionTypes;
    private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
    private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;

    private FrontendAnalysisData(
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<GdType> expressionTypes,
            @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls
    ) {
        this.annotationsByAst = Objects.requireNonNull(annotationsByAst, "annotationsByAst must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
        this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
        this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
        this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
    }

    /// Creates an empty analysis data carrier with the full side-table topology already present.
    public static @NotNull FrontendAnalysisData bootstrap() {
        return new FrontendAnalysisData(
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>(),
                new FrontendAstSideTable<>()
        );
    }

    /// Publishes the latest completed phase boundary into this shared analysis data object.
    ///
    /// Later semantic phases can refresh the boundary snapshot again after they mutate the
    /// underlying side tables or append additional diagnostics to the shared manager.
    public void publishPhaseBoundary(
            @NotNull FrontendModuleSkeleton moduleSkeleton,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        this.moduleSkeleton = Objects.requireNonNull(moduleSkeleton, "moduleSkeleton must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
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

    public @NotNull FrontendAstSideTable<GdType> expressionTypes() {
        return expressionTypes;
    }

    public @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers() {
        return resolvedMembers;
    }

    public @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls() {
        return resolvedCalls;
    }

    private <T> @NotNull T requirePublished(@Nullable T value, @NotNull String fieldName) {
        if (value == null) {
            throw new IllegalStateException(fieldName + " has not been published yet");
        }
        return value;
    }
}
