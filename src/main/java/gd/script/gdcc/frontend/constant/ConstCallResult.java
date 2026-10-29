package gd.script.gdcc.frontend.constant;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Source-free result of one registered const member call handler (const plan §5.5).
///
/// Handlers receive no AST and no Scope; the AST-facing evaluator wraps this outcome into an
/// `Evaluation` by attaching the offending node, and the owning phase maps it to diagnostics.
/// Handler domain errors become `Rejected`; an unexpected exception or a wrong-typed `Resolved`
/// value is a programmer invariant violation, not a source error.
public sealed interface ConstCallResult {

    /// The call produced a frozen constant value of the registered return type.
    record Resolved(@NotNull GdConstantValue value) implements ConstCallResult {
        public Resolved {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /// The call is outside the handler's admitted domain (division by zero, out-of-range
    /// arguments, ...); the const owner emits at most one diagnostic for it.
    record Rejected(@NotNull String reason) implements ConstCallResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
