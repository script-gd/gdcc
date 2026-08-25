package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AwaitExpression;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.FunctionDef;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/// Working entry for an `await <call>` whose coroutine classification is deferred to the
/// post-suite fixed-point pass (`FrontendAwaitCoroutineAnalyzer`).
///
/// The await's result type is already published during `EXPR_TYPE` (callee return type, `Variant`
/// for void callees); what remains undecided at that point is whether the callee is a coroutine,
/// because the callee's own body may be resolved after the caller's. The fixed-point pass consumes
/// these entries: a coroutine callee marks `enclosingFunction`; a statically known non-coroutine
/// callee produces the `sema.redundant_await` warning. A coroutine callee reached through a
/// `STATIC_METHOD` call produces neither — the compile gate owns that diagnosis.
public record FrontendAwaitCallPending(
        @NotNull AwaitExpression awaitExpression,
        @NotNull LirFunctionDef enclosingFunction,
        @NotNull FunctionDef calleeFunction,
        @NotNull FrontendCallResolutionKind callKind,
        @NotNull Path sourcePath
) {
    public FrontendAwaitCallPending {
        Objects.requireNonNull(awaitExpression, "awaitExpression must not be null");
        Objects.requireNonNull(enclosingFunction, "enclosingFunction must not be null");
        Objects.requireNonNull(calleeFunction, "calleeFunction must not be null");
        Objects.requireNonNull(callKind, "callKind must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
    }
}
