package gd.script.gdcc.frontend.sema.analyzer.support;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;

/// Capability gate applied by `FrontendConstantEvaluator` while reducing an expression.
///
/// The legacy enum pre-pass runs a dedicated policy that keeps its int-only AST whitelist and
/// earlier-member view; the general policy admits the full implemented const-evaluation
/// surface. A denied capability must produce `Evaluation.Rejected`, never silent evaluation.
public interface EvaluationPolicy {

    /// Whether the evaluator may exercise the given capability for this evaluation.
    boolean permits(@NotNull Capability capability);

    /// The evaluation surface this policy describes. The evaluator uses it only to phrase
    /// `Rejected` reasons in the surface's own vocabulary — the enum pre-pass keeps its
    /// int-only wording — never to admit or deny a capability.
    default @NotNull EvaluationDomain domain() {
        return EvaluationDomain.GENERAL;
    }

    /// The general const-evaluation surface: scalar and geometry operators,
    /// short-circuit/conditional expressions, the constructor whitelist, member projections,
    /// and registry dispatch on attribute chains.
    static @NotNull EvaluationPolicy general() {
        return new CapabilitySet(
                Set.of(
                        Capability.INT_ARITHMETIC,
                        Capability.INT_POWER,
                        Capability.LONG_MIN_LITERAL,
                        Capability.FLOAT_ARITHMETIC,
                        Capability.BOOL_OPERATORS,
                        Capability.STRING_OPERATORS,
                        Capability.COMPARISON,
                        Capability.CONSTRUCTOR,
                        Capability.BUILTIN_OPERATORS,
                        Capability.MEMBER_PROJECTION,
                        Capability.TERNARY,
                        Capability.ATTRIBUTE_ACCESS,
                        Capability.REGISTERED_MEMBER_CALL
                ),
                EvaluationDomain.GENERAL
        );
    }

    /// The enum pre-pass surface: integer literals and the legacy int operator set only, so the
    /// adapted enum evaluator keeps rejecting floats, strings, power, comparisons, calls, and
    /// ternaries exactly like the legacy implementation.
    static @NotNull EvaluationPolicy enumPrePass() {
        return new CapabilitySet(Set.of(Capability.INT_ARITHMETIC), EvaluationDomain.ENUM);
    }

    /// Feature-gate keys — one entry per spec clause of the const evaluation whitelist.
    enum Capability {
        /// Integer literal plus unary `+ - ~` and binary `+ - * / % << >> & | ^` under int64
        /// semantics.
        INT_ARITHMETIC,
        /// Non-negative integer power `**` via repeated squaring, never `Math.pow`.
        INT_POWER,
        /// The `long` minimum-value literal edge; the enum policy keeps rejecting it to
        /// preserve legacy behavior.
        LONG_MIN_LITERAL,
        /// Float literal plus unary `+ -` and binary `+ - * / **`.
        FLOAT_ARITHMETIC,
        /// `bool` literals plus `not`/`and`/`or`/`==`/`!=`; `and`/`or` still require
        /// whole-tree validation before only the taken side is computed.
        BOOL_OPERATORS,
        /// `String` literals plus `+`/`==`/`!=` on decoded payloads.
        STRING_OPERATORS,
        /// Same-exact-type `int`/`float` comparisons `== != < <= > >=`.
        COMPARISON,
        /// The constructor whitelist (scalar conversions and controlled builtins).
        CONSTRUCTOR,
        /// Unary `+`/`-`, same-type component `+ - * /`, `*i` `%`, scalar pairs, and geometry
        /// `==`/`!=` on the controlled builtin types.
        BUILTIN_OPERATORS,
        /// Read-only member projections (`x`/`y`/`z`/`w`, `position`/`size`/`end`, ...).
        MEMBER_PROJECTION,
        /// Conditional expressions with a bool condition and same-exact-type branches.
        TERNARY,
        /// Any attribute chain step (namespace member read, projection, registered call).
        ATTRIBUTE_ACCESS,
        /// Registered const member calls dispatched through the context's registry.
        REGISTERED_MEMBER_CALL
    }

    /// Evaluation-surface identity used to phrase `Rejected` reasons.
    enum EvaluationDomain {
        /// The general const-evaluation surface.
        GENERAL,
        /// The enum pre-pass surface, which keeps the legacy int-only rejection wording.
        ENUM
    }

    /// Fixed-capability policy implementation. The production surfaces come from `general()` and
    /// `enumPrePass()`; tests may compose custom sets to probe individual capability gates.
    ///
    /// @param capabilities admitted capabilities (stored immutably)
    /// @param domain       rejection-phrasing surface
    record CapabilitySet(
            @NotNull Set<Capability> capabilities,
            @NotNull EvaluationDomain domain
    ) implements EvaluationPolicy {
        public CapabilitySet {
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
            Objects.requireNonNull(domain, "domain must not be null");
        }

        @Override
        public boolean permits(@NotNull Capability capability) {
            return capabilities.contains(capability);
        }
    }
}
