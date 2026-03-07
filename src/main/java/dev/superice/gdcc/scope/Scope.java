package dev.superice.gdcc.scope;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Minimal lexical scope contract for unqualified identifier lookup.
///
/// Scope keeps value/function lookup separate from the dedicated type/meta namespace.
/// Type/meta lookup uses `resolveTypeMeta(...)` instead of being mixed into `resolveValue(...)`.
public interface Scope {
    @Nullable Scope getParentScope();

    void setParentScope(@Nullable Scope parentScope);

    @Nullable ScopeValue resolveValueHere(@NotNull String name);

    @NotNull List<? extends FunctionDef> resolveFunctionsHere(@NotNull String name);

    @Nullable ScopeTypeMeta resolveTypeMetaHere(@NotNull String name);

    default @Nullable ScopeValue resolveValue(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        var value = resolveValueHere(name);
        if (value != null) {
            return value;
        }
        var parentScope = getParentScope();
        return parentScope != null ? parentScope.resolveValue(name) : null;
    }

    default @NotNull List<? extends FunctionDef> resolveFunctions(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        var functions = resolveFunctionsHere(name);
        if (!functions.isEmpty()) {
            return functions;
        }
        var parentScope = getParentScope();
        return parentScope != null ? parentScope.resolveFunctions(name) : List.of();
    }

    default @Nullable ScopeTypeMeta resolveTypeMeta(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        var typeMeta = resolveTypeMetaHere(name);
        if (typeMeta != null) {
            return typeMeta;
        }
        var parentScope = getParentScope();
        return parentScope != null ? parentScope.resolveTypeMeta(name) : null;
    }
}
