package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/// Pure deterministic handler of one registered const member call (const plan §5.5).
///
/// Implementations are trusted built-ins performing explicit pure Java computation only: no
/// AST, Scope, or Godot runtime access; no scene/resource/clock/random/external state; no
/// mutation of the receiver or arguments. Domain failures return `ConstCallResult.Rejected`;
/// unexpected exceptions or wrong-typed `Resolved` values are programmer invariant violations.
@FunctionalInterface
public interface ConstMemberEvaluator {

    /// Evaluates the member call on frozen inputs.
    ///
    /// @param receiver            frozen receiver constant of the key's owner type
    /// @param normalizedArguments frozen arguments already converted to the selected declared
    ///                            parameter types — fixed arity, omitted defaults never filled in
    @NotNull ConstCallResult evaluate(
            @NotNull GdConstantValue receiver,
            @NotNull List<GdConstantValue> normalizedArguments
    );
}
