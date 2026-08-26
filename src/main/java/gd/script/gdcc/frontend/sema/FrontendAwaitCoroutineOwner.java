package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.LambdaExpression;
import gd.script.gdcc.lir.LirFunctionDef;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Identity handle of the callable owner that an `await` coroutine marking targets
/// (`frontend_await_implementation.md` §8).
///
/// Named functions and constructors already have their skeleton `LirFunctionDef` published by the
/// time `EXPR_TYPE` classifies an await, so they are carried directly. Lambda owners instead carry
/// the `LambdaExpression` AST identity: the synthetic `_lambda_<n>` shell is only synthesized by
/// the lowering function-preparation pass, long after sema marking, so the marker is bridged to
/// the shell at shell-creation time (both `setCoroutine(true)` and `markCoroutineFunction(shell)`).
///
/// Nested lambdas are keyed by their own identity — never by `FrontendLambdaPlan.enclosingCallable()`
/// (which points at the nearest non-lambda callable and would misattribute the marking outward).
public sealed interface FrontendAwaitCoroutineOwner {

    /// Named function / constructor owner: the published skeleton `LirFunctionDef`.
    record NamedFunction(@NotNull LirFunctionDef function) implements FrontendAwaitCoroutineOwner {
        public NamedFunction {
            Objects.requireNonNull(function, "function must not be null");
        }
    }

    /// Lambda owner: the source `LambdaExpression` identity whose shell does not exist yet.
    record Lambda(@NotNull LambdaExpression lambda) implements FrontendAwaitCoroutineOwner {
        public Lambda {
            Objects.requireNonNull(lambda, "lambda must not be null");
        }
    }
}
