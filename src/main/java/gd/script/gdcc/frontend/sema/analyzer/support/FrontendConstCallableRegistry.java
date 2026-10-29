package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.scope.ClassRegistry;
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

    /// Starts a metadata-validating builder bound to the generation's extension registry (§5.5).
    static @NotNull Builder builder(@NotNull ClassRegistry classRegistry) {
        return new FrontendConstCallableRegistryBuilder(classRegistry);
    }

    /// Finds the entry for one already-selected exact signature, or null when unregistered.
    @Nullable Entry find(@NotNull ConstCallableKey key);

    /// Registration surface of one generation-scoped registry.
    ///
    /// Every `register` call validates the claimed signature against the bound `ClassRegistry`:
    /// the key must resolve to a real builtin instance method, its parameter types must be the
    /// selected declared types, the declared return type must match, and the metadata method must
    /// carry `is_const` (§5.5 treats that flag as registration-time mismatch evidence, not as an
    /// automatic purity proof). Duplicate keys, signatures outside the admitted surface, and
    /// registrations after `build()` are programmer errors, not source diagnostics.
    interface Builder {

        /// Registers one pure handler under its exact selected signature.
        @NotNull Builder register(
                @NotNull ConstCallableKey key,
                @NotNull GdType returnType,
                @NotNull ConstMemberEvaluator evaluator
        );

        /// Freezes the registered entries into an immutable registry shared by the generation.
        @NotNull FrontendConstCallableRegistry build();
    }

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
