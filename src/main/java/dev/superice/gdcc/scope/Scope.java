package dev.superice.gdcc.scope;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Restriction-aware lexical binding protocol shared by frontend binding and shared metadata helpers.
///
/// `Scope` intentionally models three independent namespaces:
/// - value bindings via `resolveValue(...)`
/// - function overload sets via `resolveFunctions(...)`
/// - type/meta bindings via `resolveTypeMeta(...)`
///
/// The namespaces stay separate on purpose:
/// - value lookup follows nearest-hit shadowing
/// - function lookup follows nearest non-empty scope level
/// - type/meta lookup stays strict and independent from runtime value lookup
///
/// The protocol uses a minimal `ResolveRestriction` input and a richer `ScopeLookupResult` output so
/// callers can preserve Godot-style shadowing when the current layer is found-but-blocked in a static
/// context.
///
/// Implementations are expected to remain lightweight metadata containers only.
/// They should not own AST nodes, backend-only state, or code-generation details.
public interface Scope {
    /// Returns the lexical parent scope.
    ///
    /// `null` means this scope is the root of the current lookup chain.
    @Nullable Scope getParentScope();

    /// Updates the lexical parent scope.
    ///
    /// Implementations may reject invalid parent relationships if they need stronger invariants,
    /// but the default protocol treats parent wiring as a simple tree/chain construction step.
    void setParentScope(@Nullable Scope parentScope);

    /// Resolves a value binding in the current scope level only.
    ///
    /// The result may be:
    /// - `FOUND_ALLOWED` when the binding exists and may be consumed here
    /// - `FOUND_BLOCKED` when the binding exists but the current restriction rejects it
    /// - `NOT_FOUND` when lookup should continue to the parent chain
    @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    );

    /// Compatibility bridge for callers that still want the legacy unrestricted view.
    default @Nullable ScopeValue resolveValueHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return resolveValueHere(name, ResolveRestriction.unrestricted()).allowedValueOrNull();
    }

    /// Resolves function candidates in the current scope level only.
    ///
    /// A same-name overload set that is fully blocked by the current restriction must still return
    /// `FOUND_BLOCKED` so outer/global names stay shadowed, matching Godot's static-context behavior.
    @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    );

    /// Compatibility bridge for callers that still want the legacy unrestricted overload view.
    default @NotNull List<? extends FunctionDef> resolveFunctionsHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        var result = resolveFunctionsHere(name, ResolveRestriction.unrestricted());
        return result.isAllowed() ? result.requireValue() : List.of();
    }

    /// Resolves a type/meta binding in the current scope level only.
    ///
    /// This namespace is separate from values because identifiers such as class names and enum types
    /// participate in type analysis differently from runtime value bindings.
    ///
    /// Current type/meta lookup follows an explicit always-allowed policy for current
    /// `ScopeTypeMeta` bindings:
    /// - implementations must not return `FOUND_BLOCKED` for today's `ScopeTypeMeta` bindings
    /// - the result may only be `FOUND_ALLOWED` or `NOT_FOUND`
    /// - `restriction` is carried only so callers can use one protocol shape for all three namespaces
    @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMetaHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    );

    /// Compatibility bridge for callers that still want the legacy unrestricted type/meta view.
    default @Nullable ScopeTypeMeta resolveTypeMetaHere(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return resolveTypeMetaHere(name, ResolveRestriction.unrestricted()).allowedValueOrNull();
    }

    /// Resolves a value binding through the lexical parent chain using the supplied restriction.
    ///
    /// Only `NOT_FOUND` recurses to the parent scope. Both `FOUND_ALLOWED` and `FOUND_BLOCKED` stop
    /// the search so blocked hits continue to shadow outer/global names.
    default @NotNull ScopeLookupResult<ScopeValue> resolveValue(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var value = resolveValueHere(name, restriction);
        if (value.isFound()) {
            return value;
        }
        var parentScope = getParentScope();
        return parentScope != null ? parentScope.resolveValue(name, restriction) : ScopeLookupResult.notFound();
    }

    /// Compatibility bridge for callers that still want the legacy unrestricted value view.
    default @Nullable ScopeValue resolveValue(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return resolveValue(name, ResolveRestriction.unrestricted()).allowedValueOrNull();
    }

    /// Resolves function candidates through the lexical parent chain using the supplied restriction.
    ///
    /// Lookup stops at the first scope level that contributes either:
    /// - an allowed overload set, or
    /// - a blocked overload set that still shadows outer/global names
    default @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctions(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var functions = resolveFunctionsHere(name, restriction);
        if (functions.isFound()) {
            return functions;
        }
        var parentScope = getParentScope();
        return parentScope != null ? parentScope.resolveFunctions(name, restriction) : ScopeLookupResult.notFound();
    }

    /// Compatibility bridge for callers that still want the legacy unrestricted overload view.
    default @NotNull List<? extends FunctionDef> resolveFunctions(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        var result = resolveFunctions(name, ResolveRestriction.unrestricted());
        return result.isAllowed() ? result.requireValue() : List.of();
    }

    /// Resolves a type/meta binding through the lexical parent chain using the supplied restriction.
    ///
    /// The first found result wins, mirroring value shadowing while keeping type/meta lookup strict
    /// and independent of the runtime value namespace.
    ///
    /// Under the current contract, type/meta lookup never uses `FOUND_BLOCKED`:
    /// only `FOUND_ALLOWED` stops the search with a binding, while `NOT_FOUND` continues to the parent.
    default @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMeta(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");
        var typeMeta = resolveTypeMetaHere(name, restriction);
        if (typeMeta.isFound()) {
            return typeMeta;
        }
        var parentScope = getParentScope();
        return parentScope != null ? parentScope.resolveTypeMeta(name, restriction) : ScopeLookupResult.notFound();
    }

    /// Compatibility bridge for callers that still want the legacy unrestricted type/meta view.
    default @Nullable ScopeTypeMeta resolveTypeMeta(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return resolveTypeMeta(name, ResolveRestriction.unrestricted()).allowedValueOrNull();
    }
}
