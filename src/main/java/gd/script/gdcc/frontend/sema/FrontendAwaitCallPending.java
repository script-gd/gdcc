package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AwaitExpression;
import gd.script.gdcc.scope.FunctionDef;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/// Working entry for an `await <call>` whose coroutine classification is deferred to the
/// post-suite fixed-point pass (`FrontendAwaitCoroutineAnalyzer`).
///
/// The await's provisional result type is published during `EXPR_TYPE` (callee return type,
/// `Variant` for void callees); what remains undecided is whether the callee is a coroutine because
/// its body may be resolved after the caller's. The fixed-point pass consumes these entries: a
/// coroutine callee marks `enclosingOwner`; a non-coroutine Signal return refines the await result
/// and marks the caller, a Variant return marks the caller without refinement, and only another hard
/// return type produces `sema.redundant_await`. A coroutine callee reached through a `STATIC_METHOD`
/// call produces none of these — the compile gate owns that diagnosis.
///
/// `enclosingOwner` is a `FrontendAwaitCoroutineOwner` handle: named/constructor owners carry
/// their skeleton `LirFunctionDef`, while lambda owners carry the `LambdaExpression` identity
/// because their shell is only synthesized during lowering.
public record FrontendAwaitCallPending(
        @NotNull AwaitExpression awaitExpression,
        @NotNull FrontendAwaitCoroutineOwner enclosingOwner,
        @NotNull FunctionDef calleeFunction,
        @NotNull FrontendCallResolutionKind callKind,
        @NotNull Path sourcePath
) {
    public FrontendAwaitCallPending {
        Objects.requireNonNull(awaitExpression, "awaitExpression must not be null");
        Objects.requireNonNull(enclosingOwner, "enclosingOwner must not be null");
        Objects.requireNonNull(calleeFunction, "calleeFunction must not be null");
        Objects.requireNonNull(callKind, "callKind must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
    }
}
