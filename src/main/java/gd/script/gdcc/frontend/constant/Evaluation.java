package gd.script.gdcc.frontend.constant;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Outcome of one compile-time constant evaluation (const plan §4.2/§4.3).
///
/// Stored per declaration-AST identity in `FrontendAnalysisData.constantFacts` as a pure fact:
/// the evaluator never re-runs a recorded evaluation and never emits diagnostics — the owning
/// phase decides whether and how to surface a `Rejected` reason anchored on `offendingNode` (§9).
public sealed interface Evaluation {

    /// The expression reduced to a frozen constant value.
    record Resolved(@NotNull GdConstantValue value) implements Evaluation {
        public Resolved {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /// The expression failed evaluation or falls outside the admitted constant subset.
    ///
    /// @param reason        human-readable failure reason; the diagnostic owner classifies it as
    ///                      an unsupported shape, an evaluation failure, or a name rule per §9
    /// @param offendingNode smallest AST anchor for the failure, when one exists
    record Rejected(@NotNull String reason, @Nullable Node offendingNode) implements Evaluation {
        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
