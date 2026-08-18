package gd.script.gdcc.frontend.sema;

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
public record FrontendLambdaPlan(
        @NotNull LambdaExpression lambda,
        @NotNull String syntheticName,
        @NotNull FrontendLambdaCapturePlan capturePlan,
        @NotNull GdType returnType,
        @NotNull Node enclosingCallable,
        @NotNull String owningClassCanonicalName
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
    }

    public @NotNull List<LambdaCaptureEntry> captures() {
        return capturePlan.captures();
    }

    public boolean capturesSelf() {
        return capturePlan.capturesSelf();
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
                && first.owningClassCanonicalName().equals(second.owningClassCanonicalName());
    }
}
