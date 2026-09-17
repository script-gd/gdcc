package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Frozen semantic identity of one `LambdaExpression`.
///
/// Keyed in `FrontendAnalysisData.lambdaPlans()` by the lambda AST node. The first published
/// payload is already complete: synthetic `_lambda_<k>` name, declaration-site captures, and
/// the once-resolved return type.
///
/// @param lambda                   AST identity of the lambda expression
/// @param syntheticName            compiler-owned function name, conventionally `_lambda_<k>`
/// @param capturePlan              ordered captures and `capturesSelf`
/// @param returnType               declared return type resolved once at nested-resolve entry
///                                  (`resolveTypeOrVariant` semantics); consumed identically by
///                                  type-check (return slot) and lowering (shell return type) so the
///                                  two cannot drift
/// @param enclosingCallable        nearest non-lambda callable AST (`FunctionDeclaration` /
///                                  `ConstructorDeclaration`); identity, not a reconstructed node
/// @param owningClassCanonicalName canonical name of the owning `LirClassDef`
/// @param identityOrdinal          source pre-order sequence number of this lambda within its
///                                  outermost named function body (assigned by
///                                  `FrontendLambdaIdentityAnalyzer`, isomorphic to the lowering
///                                  pass's lambda discovery traversal)
/// @param callSiteContext          normalized call-site context descriptor (never null; the
///                                  `stmt(...)` fallback always applies — NULL contexts exist only
///                                  for backend-synthesized standalone Callable identities)
public record FrontendLambdaPlan(
        @NotNull LambdaExpression lambda,
        @NotNull String syntheticName,
        @NotNull FrontendLambdaCapturePlan capturePlan,
        @NotNull GdType returnType,
        @NotNull Node enclosingCallable,
        @NotNull String owningClassCanonicalName,
        int identityOrdinal,
        @NotNull String callSiteContext
) {
    public FrontendLambdaPlan {
        Objects.requireNonNull(lambda, "lambda must not be null");
        Objects.requireNonNull(syntheticName, "syntheticName must not be null");
        if (syntheticName.isBlank()) {
            throw new IllegalArgumentException("syntheticName must not be blank");
        }
        Objects.requireNonNull(capturePlan, "capturePlan must not be null");
        Objects.requireNonNull(returnType, "returnType must not be null");
        Objects.requireNonNull(enclosingCallable, "enclosingCallable must not be null");
        Objects.requireNonNull(owningClassCanonicalName, "owningClassCanonicalName must not be null");
        if (owningClassCanonicalName.isBlank()) {
            throw new IllegalArgumentException("owningClassCanonicalName must not be blank");
        }
        if (identityOrdinal < 0) {
            throw new IllegalArgumentException("identityOrdinal must be >= 0: " + identityOrdinal);
        }
        Objects.requireNonNull(callSiteContext, "callSiteContext must not be null");
        if (callSiteContext.isBlank()) {
            throw new IllegalArgumentException("callSiteContext must not be blank");
        }
    }

    public @NotNull List<LambdaCaptureEntry> captures() {
        return capturePlan.captures();
    }

    public boolean capturesSelf() {
        return capturePlan.capturesSelf();
    }

    /// Stable source identity of this lambda for hot-reload rebinding
    /// (hot_reload_implementation_plan.md §5.11): `<Class>::<enclosingFunc>#<ordinal>`.
    /// `<Class>` is the canonical owning-class name (module-unique, so no file name is
    /// needed); `<enclosingFunc>` is the outermost NAMED callable (`enclosingCallable` is
    /// already normalized to it, with constructors rendered as `_init`); `ordinal` is the
    /// deterministic source pre-order sequence number within that function body. Body edits,
    /// non-lambda line shifts and whole-function moves keep the key stable; only a lambda
    /// inserted/removed before this one inside the same function shifts the number (the
    /// schema/call-site gates then decide rebind vs fail-closed).
    public @NotNull String sourceIdentityKey() {
        var enclosingName = switch (enclosingCallable) {
            case FunctionDeclaration functionDeclaration -> functionDeclaration.name();
            case ConstructorDeclaration _ -> "_init";
            default -> throw new IllegalStateException(
                    "Lambda '" + syntheticName + "' has an unexpected enclosing callable node: "
                            + enclosingCallable.getClass().getSimpleName()
                            + " (expected FunctionDeclaration or ConstructorDeclaration)"
            );
        };
        return owningClassCanonicalName + "::" + enclosingName + "#" + identityOrdinal;
    }

    /// Logical equivalence for idempotent merge. The side table is already keyed by `lambda`
    /// identity, so this compares payload only. `enclosingCallable` is identity-equal.
    public static boolean samePlan(@NotNull FrontendLambdaPlan first, @NotNull FrontendLambdaPlan second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        return first.syntheticName().equals(second.syntheticName())
                && FrontendLambdaCapturePlan.samePlan(first.capturePlan(), second.capturePlan())
                && FrontendAnalysisData.sameType(first.returnType(), second.returnType())
                && first.enclosingCallable() == second.enclosingCallable()
                && first.owningClassCanonicalName().equals(second.owningClassCanonicalName())
                && first.identityOrdinal() == second.identityOrdinal()
                && first.callSiteContext().equals(second.callSiteContext());
    }
}
