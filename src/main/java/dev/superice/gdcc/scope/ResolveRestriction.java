package dev.superice.gdcc.scope;

import org.jetbrains.annotations.NotNull;

/// Minimal unqualified-lookup restriction carried by the shared `Scope` protocol.
///
/// The restriction models **which class-member categories are allowed to stop name lookup in the
/// current context**. It is intentionally small and does not carry diagnostics text:
/// - `Scope` only answers whether the current context may legally consume a hit.
/// - The frontend binder still owns user-facing error reporting and AST-specific reasoning.
///
/// Phase 4 follow-up semantics frozen by the planning docs:
/// - `staticContext()` allows class constants, static properties, and static methods.
/// - `instanceContext()` allows class constants, instance/static properties, and instance/static methods.
/// - `unrestricted()` keeps compatibility behavior for legacy callers and tests.
///
/// Type/meta lookup still travels through the same signature only for protocol uniformity:
/// - current `ScopeTypeMetaKind` bindings are always restriction-allowed
/// - `resolveTypeMeta(..., restriction)` may therefore return only `FOUND_ALLOWED` or `NOT_FOUND`
/// - any legality split while consuming a `TYPE_META` binding is deferred to binder/static access
///   analysis instead of being encoded in `ResolveRestriction` today
///
/// So the current restriction only affects unqualified value/function lookup at class-member layers.
public record ResolveRestriction(
        boolean allowClassConstants,
        boolean allowInstanceProperties,
        boolean allowStaticProperties,
        boolean allowInstanceMethods,
        boolean allowStaticMethods
) {
    private static final @NotNull ResolveRestriction UNRESTRICTED =
            new ResolveRestriction(true, true, true, true, true);
    private static final @NotNull ResolveRestriction INSTANCE_CONTEXT =
            new ResolveRestriction(true, true, true, true, true);
    private static final @NotNull ResolveRestriction STATIC_CONTEXT =
            new ResolveRestriction(true, false, true, false, true);

    public static @NotNull ResolveRestriction unrestricted() {
        return UNRESTRICTED;
    }

    public static @NotNull ResolveRestriction instanceContext() {
        return INSTANCE_CONTEXT;
    }

    public static @NotNull ResolveRestriction staticContext() {
        return STATIC_CONTEXT;
    }
}
