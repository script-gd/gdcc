package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Scope-facing name-resolution view handed to `FrontendConstantEvaluator` (const plan §4.3).
///
/// One instance describes exactly one evaluation context — a class-skeleton const pre-pass item,
/// a suite-local const initializer, and so on. Resolution must answer by real scope identity
/// (never by string-map guessing) and must not silently fall back past a claimed or blocked
/// name: forward references, failed constants, and ancestor shadows are `Blocked`, which is
/// terminal.
public interface LookupContext {

    /// Resolves one bare source name into the four-state result.
    @NotNull LookupResult resolve(@NotNull String name);

    /// Resolves `memberName` inside a namespace token previously produced by this context.
    ///
    /// `namespaceIdentity` is opaque to the evaluator — it is whatever token this context
    /// emitted inside `LookupResult.ResolvedNamespace` (script enum group, script class, ...).
    @NotNull LookupResult resolveMember(@NotNull Object namespaceIdentity, @NotNull String memberName);

    /// The immutable const-callable registry shared for the whole analysis generation (§5.5).
    /// The production default is the empty registry; a policy that denies
    /// `EvaluationPolicy.Capability.REGISTERED_MEMBER_CALL` never consults it.
    @NotNull FrontendConstCallableRegistry callableRegistry();

    /// Selects the exact builtin method signature a frozen member call would bind to (§5.5).
    ///
    /// Selection is ordinary overload resolution by argument types against the generation's
    /// metadata — the same semantics as a regular builtin instance-method call, never a
    /// name-only guess. The evaluator feeds the returned `ConstCallableKey` into
    /// `callableRegistry()`; a `Rejected` selection is terminal for the call. Constructor
    /// calls are not routed here: the §5.3 constructor whitelist is a fixed recipe table the
    /// evaluator matches directly.
    @NotNull SelectionResult selectCall(
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argumentTypes
    );

    /// Name-resolution result (§4.3: resolved constant / nonconstant / blocked / unknown).
    /// `ResolvedNamespace` splits qualified namespaces out of "resolved" so chains such as
    /// `Other.Inner.CONST` and `State.IDLE` can keep descending through `resolveMember`.
    sealed interface LookupResult {

        /// The name resolves to a frozen constant value.
        record ResolvedConstant(@NotNull GdConstantValue value) implements LookupResult {
            public ResolvedConstant {
                Objects.requireNonNull(value, "value must not be null");
            }
        }

        /// The name resolves to a constant namespace; `namespaceIdentity` is an opaque token
        /// interpreted only by the issuing `LookupContext` via `resolveMember`.
        record ResolvedNamespace(@NotNull Object namespaceIdentity) implements LookupResult {
            public ResolvedNamespace {
                Objects.requireNonNull(namespaceIdentity, "namespaceIdentity must not be null");
            }
        }

        /// The name exists but is not usable as a compile-time constant (mutable var,
        /// parameter, capture, enum group consumed as a value, ...).
        record NonConstant(@NotNull String reason) implements LookupResult {
            public NonConstant {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }

        /// The name is claimed but terminal-blocked (failed const, forward reference, ancestor
        /// shadow, ...); evaluation must not fall through to outer or global names.
        record Blocked(@NotNull String reason) implements LookupResult {
            public Blocked {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }

        /// The name is unknown in every visible layer.
        record Unknown() implements LookupResult {
        }
    }

    /// Exact-signature selection result for a frozen member call (§5.5).
    sealed interface SelectionResult {

        /// The call binds to one exact signature: `key` is the registry identity (selected
        /// method owner + declared fixed parameter types) and `returnType` its declared
        /// return type.
        record Selected(@NotNull ConstCallableKey key, @NotNull GdType returnType)
                implements SelectionResult {
            public Selected {
                Objects.requireNonNull(key, "key must not be null");
                Objects.requireNonNull(returnType, "returnType must not be null");
            }
        }

        /// No signature accepts the frozen argument types, or the dispatch shape lies outside
        /// the admitted surface (static/utility/Object/Variant routes, ...); the evaluator
        /// surfaces this as `Evaluation.Rejected`.
        record Rejected(@NotNull String reason) implements SelectionResult {
            public Rejected {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }
}
