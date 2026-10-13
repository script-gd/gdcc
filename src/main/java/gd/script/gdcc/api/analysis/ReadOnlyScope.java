package gd.script.gdcc.api.analysis;

import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupResult;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ResolveRestriction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// Read-only view over a published `Scope` (`frontend_lsp_foundation_implementation.md` §2.1:
/// query services must reach container-held model objects only through views that expose no
/// mutator).
///
/// The `Scope` interface itself carries one mutator — `setParentScope` — whose implementations
/// rewire the lexical chain without any freeze gate. Every scope the query service reads is
/// therefore wrapped here: all lookup/enumeration methods delegate to the underlying published
/// scope (scopes returned from them are wrapped recursively), while `setParentScope` throws
/// `UnsupportedOperationException`. The structural freeze on the side tables stays as the
/// second line of defense underneath.
final class ReadOnlyScope implements Scope {
    private final @NotNull Scope delegate;

    private ReadOnlyScope(@NotNull Scope delegate) {
        this.delegate = delegate;
    }

    static @NotNull Scope wrap(@NotNull Scope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        return scope instanceof ReadOnlyScope ? scope : new ReadOnlyScope(scope);
    }

    @Override
    public @Nullable Scope getParentScope() {
        var parent = delegate.getParentScope();
        return parent == null ? null : wrap(parent);
    }

    @Override
    public void setParentScope(@Nullable Scope parentScope) {
        throw new UnsupportedOperationException("published snapshot scopes are read-only");
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        return delegate.resolveValueHere(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        return delegate.resolveFunctionsHere(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMetaHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        return delegate.resolveTypeMetaHere(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValue(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        // Chain-walking lookups MUST run on the delegate: ClassScope overrides them to skip
        // consecutive outer class scopes, and the interface default would walk the wrapped
        // parent chain instead and wrongly expose outer-class members.
        return delegate.resolveValue(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctions(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        return delegate.resolveFunctions(name, restriction);
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMeta(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        return delegate.resolveTypeMeta(name, restriction);
    }

    @Override
    public @NotNull Collection<ScopeValue> valuesHere() {
        // Snapshot the values: some scope implementations return live views over their
        // internal tables, and the query side must not observe structural mutation.
        return List.copyOf(delegate.valuesHere());
    }

    @Override
    public @Nullable Scope enumerationParentScope() {
        var parent = delegate.enumerationParentScope();
        return parent == null ? null : wrap(parent);
    }

    @Override
    public @Nullable ClassDef currentClassOrNull() {
        return delegate.currentClassOrNull();
    }

    @Override
    public @Nullable ClassDef owningClassOrNull() {
        return delegate.owningClassOrNull();
    }
}
