package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// One resolved frontend binding fact attached to an AST use site.
///
/// The record stores symbol category plus declaration provenance. For ordinary value bindings it
/// also keeps the exact value resolved by top binding so later body phases do not re-run lexical
/// lookup and accidentally bypass declaration-order or self-reference visibility rules.
///
/// @param symbolName source-facing symbol name attached to this use site
/// @param kind frontend binding category published for downstream semantic and lowering phases
/// @param declarationSite declaration provenance retained for diagnostics and tests
/// @param resolvedValue exact ordinary value selected by top binding; null for non-value bindings
/// @param valueAccessStatus allowed/blocked lookup status for `resolvedValue`; null iff it is null
public record FrontendBinding(
        @NotNull String symbolName,
        @NotNull FrontendBindingKind kind,
        @Nullable Object declarationSite,
        @Nullable ScopeValue resolvedValue,
        @Nullable ScopeLookupStatus valueAccessStatus
) {
    public FrontendBinding(
            @NotNull String symbolName,
            @NotNull FrontendBindingKind kind,
            @Nullable Object declarationSite
    ) {
        this(symbolName, kind, declarationSite, null, null);
    }

    public FrontendBinding {
        Objects.requireNonNull(symbolName, "symbolName must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if ((resolvedValue == null) != (valueAccessStatus == null)) {
            throw new IllegalArgumentException("resolved value and access status must be recorded together");
        }
        if (valueAccessStatus == ScopeLookupStatus.NOT_FOUND) {
            throw new IllegalArgumentException("resolved value access status must be found");
        }
    }

    public @NotNull FrontendBinding withResolvedValue(@NotNull ScopeValue updatedValue) {
        return new FrontendBinding(
                symbolName,
                kind,
                declarationSite,
                Objects.requireNonNull(updatedValue, "updatedValue must not be null"),
                Objects.requireNonNull(valueAccessStatus, "valueAccessStatus must not be null")
        );
    }
}
