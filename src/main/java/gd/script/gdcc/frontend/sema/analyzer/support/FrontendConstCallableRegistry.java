package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Compiler-internal registry of whitelisted pure const member calls (const plan §5.5).
///
/// This is neither `ClassRegistry` nor the runtime ClassDB: entries are admitted explicitly per
/// analysis generation, frozen before use, and dispatched only by exact `ConstCallableKey`
/// after ordinary method selection already ran. The production default registry is empty —
/// unregistered calls reject instead of falling back to a runtime call.
public interface FrontendConstCallableRegistry {

    /// The shared immutable registry with zero entries — the production default (§5.5).
    static @NotNull FrontendConstCallableRegistry empty() {
        return key -> null;
    }

    /// Finds the entry for one already-selected exact signature, or null when unregistered.
    /// Registration and freeze mechanics land with the P2 builder stage; this lookup contract
    /// is the frozen read side every `LookupContext` exposes.
    @Nullable Entry find(@NotNull ConstCallableKey key);

    /// One frozen registration: exact key + declared return type + pure handler.
    ///
    /// @param key        exact selected-signature identity
    /// @param returnType declared return type of the selected method
    /// @param evaluator  pure handler producing the frozen result
    record Entry(
            @NotNull ConstCallableKey key,
            @NotNull GdType returnType,
            @NotNull ConstMemberEvaluator evaluator
    ) {
        public Entry {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(returnType, "returnType must not be null");
            Objects.requireNonNull(evaluator, "evaluator must not be null");
        }
    }
}
