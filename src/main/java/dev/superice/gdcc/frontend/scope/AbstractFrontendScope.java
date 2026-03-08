package dev.superice.gdcc.frontend.scope;

import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeLookupResult;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Shared base for frontend-owned lexical scope implementations.
///
/// This base owns only the parts that are genuinely common across `ClassScope`, `CallableScope`,
/// and `BlockScope`:
/// - the lexical parent pointer
/// - the opt-in local type/meta namespace used by frontend binding
///
/// Restriction-aware value/function semantics stay in concrete subclasses because their lookup shape
/// differs by scope kind:
/// - `ClassScope` mixes direct members, inherited members, and class-member restrictions
/// - `CallableScope` prioritizes parameters over captures
/// - `BlockScope` only contains block-local bindings
public abstract class AbstractFrontendScope implements Scope {
    private final Map<String, ScopeTypeMeta> typeMetasByName = new LinkedHashMap<>();
    private @Nullable Scope parentScope;

    protected AbstractFrontendScope(@Nullable Scope parentScope) {
        this.parentScope = parentScope;
    }

    @Override
    public final @Nullable Scope getParentScope() {
        return parentScope;
    }

    @Override
    public void setParentScope(@Nullable Scope parentScope) {
        this.parentScope = parentScope;
    }

    /// Registers a type/meta binding local to the current lexical scope.
    ///
    /// The frontend binder is expected to diagnose duplicate names before reaching this layer.
    /// We still reject same-scope redefinition here to keep lookup deterministic and fail fast
    /// when a caller wires the scope graph incorrectly.
    public void defineTypeMeta(@NotNull ScopeTypeMeta typeMeta) {
        Objects.requireNonNull(typeMeta, "typeMeta");
        var previous = typeMetasByName.putIfAbsent(typeMeta.name(), typeMeta);
        if (previous != null) {
            throw duplicateNamespaceBinding("type-meta", typeMeta.name());
        }
    }

    /// Resolves a local type/meta binding owned by the current frontend scope layer.
    ///
    /// Current Phase 4 contract keeps type/meta lookup on an explicit always-allowed policy:
    /// - `restriction` is accepted only for signature uniformity with value/function lookup
    /// - today's type/meta bindings may resolve only to `FOUND_ALLOWED` or `NOT_FOUND`
    /// - any later legality split belongs to binder/static-access consumption, not this lookup step
    @Override
    public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMetaHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var typeMeta = typeMetasByName.get(name);
        return typeMeta != null ? ScopeLookupResult.foundAllowed(typeMeta) : ScopeLookupResult.notFound();
    }

    @Override
    public abstract @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    );

    @Override
    public abstract @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    );

    /// Creates a deterministic duplicate-binding error for same-scope namespace conflicts.
    protected final @NotNull IllegalArgumentException duplicateNamespaceBinding(
            @NotNull String namespace,
            @NotNull String name
    ) {
        return new IllegalArgumentException(
                "Duplicate " + namespace + " binding '" + name + "' in the same frontend scope"
        );
    }
}
