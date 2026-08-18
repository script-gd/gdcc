package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Pure capture derivation over an already-built scope graph.
///
/// The planner only inspects identifier uses and existing `ScopeValue` bindings. It never writes
/// scopes, never publishes side tables, and never emits diagnostics. Production inventory calls
/// `defineCapture` from this result; focused tests may feed hand-built graphs.
///
/// Algorithm (innermost-first, aligned with Godot, independent of Godot AST):
/// 1. Look up each use from **that identifier's own scope**, not from the lambda `CallableScope.parent`.
/// 2. Capture only when the first hit is outside this lambda's callable boundary and the kind is
///    `PARAMETER` / `LOCAL` / `CAPTURE`.
/// 3. Self-shadowing (this lambda's own parameter / local) is not a capture.
/// 4. Class / global / utility / type-meta hits stay ordinary lexical lookups.
/// 5. First source appearance freezes order.
/// 6. Nested transfer: when the source binding lives outside a parent lambda, that parent also
///    records the same name / `sourceDeclaration` unless it already shadows the name.
public final class FrontendLambdaCapturePlanner {
    private FrontendLambdaCapturePlanner() {
    }

    /// One identifier use inside a lambda body, already bound to the scope that owns the use site.
    ///
    /// @param name          identifier text
    /// @param startingScope scope of the use site; lookup walks from here, not from the lambda parent
    public record IdentifierUse(@NotNull String name, @NotNull Scope startingScope) {
        public IdentifierUse {
            Objects.requireNonNull(name, "name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(startingScope, "startingScope must not be null");
        }
    }

    /// Derives the capture list for one lambda from ordered identifier uses.
    ///
    /// `identifierUses` must already be source-order and must omit declaration sites
    /// (`Parameter.name` / `VariableDeclaration.name`). Nested lambdas are planned first by the
    /// caller (post-order); this method only sees the current lambda.
    public static @NotNull FrontendLambdaCapturePlan planCaptures(
            @NotNull CallableScope lambdaScope,
            @NotNull List<IdentifierUse> identifierUses
    ) {
        Objects.requireNonNull(lambdaScope, "lambdaScope must not be null");
        Objects.requireNonNull(identifierUses, "identifierUses must not be null");
        var capturesByName = new LinkedHashMap<String, LambdaCaptureEntry>();
        for (var use : identifierUses) {
            Objects.requireNonNull(use, "identifierUses must not contain null");
            if (capturesByName.containsKey(use.name())) {
                continue;
            }
            var capture = captureFromUse(lambdaScope, use);
            if (capture != null) {
                capturesByName.put(use.name(), capture);
            }
        }
        return FrontendLambdaCapturePlan.of(List.copyOf(capturesByName.values()));
    }

    /// Copies an inner capture onto a parent lambda when the source lives outside the parent and
    /// the parent does not already shadow the name with its own parameter / local / capture.
    ///
    /// `parentBodyScope` must be a scope inside the parent lambda (typically `LAMBDA_BODY`) so
    /// parent locals are visible. Looking up from the parent `CallableScope` alone would walk
    /// *outward* and miss those locals.
    public static @Nullable LambdaCaptureEntry transferredCapture(
            @NotNull CallableScope parentLambdaScope,
            @NotNull Scope parentBodyScope,
            @NotNull LambdaCaptureEntry innerCapture
    ) {
        Objects.requireNonNull(parentLambdaScope, "parentLambdaScope must not be null");
        Objects.requireNonNull(parentBodyScope, "parentBodyScope must not be null");
        Objects.requireNonNull(innerCapture, "innerCapture must not be null");
        var source = firstValueHit(parentBodyScope, innerCapture.name());
        if (source == null || !isCapturableKind(source.kind())) {
            return null;
        }
        if (isOwnedByCallable(parentLambdaScope, parentBodyScope, source)) {
            return null;
        }
        return new LambdaCaptureEntry(innerCapture.name(), source.type(), source.kind(), source.declaration());
    }

    /// Walks the parent-lambda chain and returns the transfer entries each intermediate lambda must
    /// record. The list is innermost-parent first; a shadowing parent stops the chain.
    public static @NotNull List<LambdaCaptureEntry> transferredCapturesAlongParents(
            @NotNull List<ParentLambda> parentsInnermostFirst,
            @NotNull LambdaCaptureEntry innerCapture
    ) {
        Objects.requireNonNull(parentsInnermostFirst, "parentsInnermostFirst must not be null");
        Objects.requireNonNull(innerCapture, "innerCapture must not be null");
        var transferred = new ArrayList<LambdaCaptureEntry>();
        for (var parent : parentsInnermostFirst) {
            Objects.requireNonNull(parent, "parentsInnermostFirst must not contain null");
            var next = transferredCapture(parent.callableScope(), parent.bodyScope(), innerCapture);
            if (next == null) {
                break;
            }
            transferred.add(next);
        }
        return List.copyOf(transferred);
    }

    /// Parent lambda view used for nested capture transfer: callable boundary plus a body scope
    /// that can see that lambda's own locals.
    public record ParentLambda(@NotNull CallableScope callableScope, @NotNull Scope bodyScope) {
        public ParentLambda {
            Objects.requireNonNull(callableScope, "callableScope must not be null");
            Objects.requireNonNull(bodyScope, "bodyScope must not be null");
        }
    }

    /// Resolves `name` from the use site and reports whether the first capturable hit is outside
    /// `lambdaScope`. Used by tests that assert self-shadowing without building a full plan.
    public static boolean isCaptureFromUse(@NotNull CallableScope lambdaScope, @NotNull IdentifierUse use) {
        return captureFromUse(lambdaScope, use) != null;
    }

    /// Type recorded for a capture: the declaration-site type already stored on the resolved
    /// `ScopeValue`. The planner does not read physical slot overlays; nested resolve replaces
    /// the inventory `Variant` placeholder through `LambdaCaptureEntry.withType`.
    public static @NotNull GdType captureTypeOf(@NotNull ScopeValue sourceBinding) {
        return Objects.requireNonNull(sourceBinding, "sourceBinding must not be null").type();
    }

    private static @Nullable LambdaCaptureEntry captureFromUse(
            @NotNull CallableScope lambdaScope,
            @NotNull IdentifierUse use
    ) {
        var hit = firstValueHit(use.startingScope(), use.name());
        if (hit == null || !isCapturableKind(hit.kind())) {
            return null;
        }
        if (isOwnedByCallable(lambdaScope, use.startingScope(), hit)) {
            return null;
        }
        return new LambdaCaptureEntry(use.name(), hit.type(), hit.kind(), hit.declaration());
    }

    private static boolean isCapturableKind(@NotNull ScopeValueKind kind) {
        return kind == ScopeValueKind.PARAMETER
                || kind == ScopeValueKind.LOCAL
                || kind == ScopeValueKind.CAPTURE;
    }

    /// Ownership for a use site: walk from the use scope toward the root. The first scope that
    /// publishes `hit` owns it. Bindings introduced at `lambdaScope` or in blocks nested inside it
    /// (before another callable) belong to this lambda.
    private static boolean isOwnedByCallable(
            @NotNull CallableScope lambdaScope,
            @NotNull Scope startingScope,
            @NotNull ScopeValue hit
    ) {
        var current = startingScope;
        while (current != null) {
            var here = current.resolveValueHere(hit.name(), ResolveRestriction.unrestricted())
                    .allowedValueOrNull();
            if (here == hit) {
                return current == lambdaScope || isNestedUnder(current, lambdaScope);
            }
            if (current == lambdaScope) {
                return false;
            }
            current = current.getParentScope();
        }
        return false;
    }

    private static boolean isNestedUnder(@NotNull Scope inner, @NotNull CallableScope lambdaScope) {
        var current = inner.getParentScope();
        while (current != null) {
            if (current == lambdaScope) {
                return true;
            }
            if (current instanceof CallableScope) {
                return false;
            }
            current = current.getParentScope();
        }
        return false;
    }

    private static @Nullable ScopeValue firstValueHit(@NotNull Scope start, @NotNull String name) {
        var result = start.resolveValue(name, ResolveRestriction.unrestricted());
        return result.isFound() ? result.requireValue() : null;
    }
}
