package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.Expression;
import gd.script.gdcc.frontend.constant.Evaluation;
import org.jetbrains.annotations.NotNull;

/// General compile-time constant evaluator (const plan §4.3).
///
/// P0 freezes this contract only — the evaluation implementation (scalar rules, short-circuit
/// whole-tree validation, constructors, projections, registry dispatch) lands with stage P2,
/// and the legacy `FrontendEnumConstantEvaluator` then becomes a thin adapter onto the
/// enum-specific `EvaluationPolicy` so the two never keep parallel integer arithmetic.
///
/// The evaluator never touches the Godot runtime, never mutates the AST, and never emits
/// diagnostics — diagnostic ownership stays with the calling phase (§9).
public final class FrontendConstantEvaluator {
    private FrontendConstantEvaluator() {
    }

    /// Evaluates one expression under the given scope view and capability policy.
    ///
    /// - `expression`: the initializer or read-site expression to reduce; never mutated.
    /// - `context`: the scope-identity lookup view of this evaluation position.
    /// - `policy`: the capability gate (enum pre-pass vs general evaluation).
    public static @NotNull Evaluation evaluate(
            @NotNull Expression expression,
            @NotNull LookupContext context,
            @NotNull EvaluationPolicy policy
    ) {
        throw new UnsupportedOperationException(
                "FrontendConstantEvaluator contract is frozen in P0; the implementation lands in P2"
        );
    }
}
