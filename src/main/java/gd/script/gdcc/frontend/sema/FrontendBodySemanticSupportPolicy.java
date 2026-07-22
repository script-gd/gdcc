package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Immutable structural support matrix for frontend body locations.
///
/// A policy answers three independent structural questions: whether the location owns feature-specific lexical
/// inventory, whether it enters the segmented `FrontendSuiteResolver` body pipeline, and which
/// [FrontendVisibleValueDomain] must be used for bare-name lookup. The answer is derived only from AST/scope
/// structure. Expression types, typed overlays, diagnostics, iteration routes, compile readiness, and lifecycle
/// state are intentionally absent.
///
/// [#EXECUTABLE_BODY] represents every body whose scope/inventory implementation is complete, including
/// `FOR_BODY`. Unsupported or not-yet-implemented features receive a precise deferred domain instead of a pending
/// or published lifecycle. [#FOR_HEADER] is a special structural location: it uses the surrounding executable
/// lookup domain but owns no body inventory and does not itself enter the suite resolver.
///
/// Mapping switches are exhaustive and deliberately have no `default` branch. Adding a new [BlockScopeKind] or
/// [CallableScopeKind] therefore requires an explicit support decision rather than silently opening the new kind
/// as an executable body.
public enum FrontendBodySemanticSupportPolicy {
    EXECUTABLE_BODY(true, true, FrontendVisibleValueDomain.EXECUTABLE_BODY),
    FOR_HEADER(false, false, FrontendVisibleValueDomain.EXECUTABLE_BODY),
    LAMBDA_SUBTREE(false, false, FrontendVisibleValueDomain.LAMBDA_SUBTREE),
    MATCH_SUBTREE(false, false, FrontendVisibleValueDomain.MATCH_SUBTREE),
    BLOCK_LOCAL_CONST_SUBTREE(false, false, FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE),
    PARAMETER_DEFAULT(false, false, FrontendVisibleValueDomain.PARAMETER_DEFAULT),
    UNKNOWN_OR_SKIPPED_SUBTREE(false, false, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);

    private final boolean publishesLexicalInventory;
    private final boolean entersSuiteResolver;
    private final @NotNull FrontendVisibleValueDomain visibleValueDomain;

    /// Creates one immutable row in the structural support matrix.
    ///
    /// The two boolean capabilities are kept as separate fields even though they currently move together for
    /// body policies. This preserves the distinction between publishing lexical inventory and entering semantic
    /// body resolution, preventing either capability from being inferred indirectly by consumers.
    ///
    /// @param publishesLexicalInventory whether the location owns and publishes feature-specific lexical inventory
    /// @param entersSuiteResolver       whether the location is a body root accepted by `FrontendSuiteResolver`
    /// @param visibleValueDomain        the request/deferred domain used by visible-value resolution at this location
    FrontendBodySemanticSupportPolicy(
            boolean publishesLexicalInventory,
            boolean entersSuiteResolver,
            @NotNull FrontendVisibleValueDomain visibleValueDomain
    ) {
        this.publishesLexicalInventory = publishesLexicalInventory;
        this.entersSuiteResolver = entersSuiteResolver;
        this.visibleValueDomain = Objects.requireNonNull(visibleValueDomain, "visibleValueDomain");
    }

    /// Reports whether this structural location publishes its feature-owned lexical inventory before body typing.
    ///
    /// The result describes structural publication capability only. It never reflects whether expression typing,
    /// route planning, diagnostics, or lowering succeeded for a particular source body.
    ///
    /// @return `true` only for locations whose complete lexical inventory is part of the supported Interface surface
    public boolean publishesLexicalInventory() {
        return publishesLexicalInventory;
    }

    /// Reports whether a body with this policy may enter `FrontendSuiteResolver`.
    ///
    /// [FrontendBodyStructuralCompleteness] consumes this capability as the first certificate check, then verifies
    /// that the current Interface surface actually contains all required structural facts.
    ///
    /// @return `true` when this policy represents a supported suite body
    public boolean entersSuiteResolver() {
        return entersSuiteResolver;
    }

    /// Reports whether this structural location is a supported suite body root.
    ///
    /// This is the single semantic entry consumed both by `FrontendInterfacePhase` (to decide which blocks become
    /// suite-entry roots with a published declaration index) and by `FrontendBodyStructuralCompleteness` (as the
    /// first certificate gate). Both consumers must read this method instead of inferring body-entry from
    /// `publishesLexicalInventory()` or `entersSuiteResolver()` independently, so the structural matrix stays the
    /// single source of truth even if the two boolean capabilities diverge for future features. Inventory-only
    /// consumers (visible-value boundary, deferred const detection, inferred-local-scope eligibility) continue to
    /// read `publishesLexicalInventory()` directly, because they answer a different question.
    ///
    /// @return `true` when this policy represents a body root that may enter `FrontendSuiteResolver`
    public boolean isSupportedSuiteBodyRoot() {
        return entersSuiteResolver;
    }

    /// Returns the visible-value lookup domain associated with this structural location.
    ///
    /// Supported bodies and for headers use [FrontendVisibleValueDomain#EXECUTABLE_BODY]. Unsupported locations
    /// retain a precise non-executable domain so request-domain, AST-boundary, and current-scope checks fail closed
    /// without consulting typed readiness.
    ///
    /// @return the immutable resolver domain for this policy
    public @NotNull FrontendVisibleValueDomain visibleValueDomain() {
        return visibleValueDomain;
    }

    /// Maps a block scope kind to its explicit structural body policy.
    ///
    /// Function, constructor, ordinary block, conditional, loop, and `FOR_BODY` scopes are executable bodies.
    /// Lambda and match-section scopes remain in their feature-specific deferred domains. The exhaustive switch is
    /// intentional: a newly added block scope kind must make a compile-time-visible support choice here.
    ///
    /// @param kind the block scope kind whose structural support is being queried
    /// @return the policy that controls inventory publication, suite entry, and visible-value domain
    /// @throws NullPointerException if `kind` is `null`
    public static @NotNull FrontendBodySemanticSupportPolicy forBlockScopeKind(@NotNull BlockScopeKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case BLOCK_STATEMENT,
                 FUNCTION_BODY,
                 CONSTRUCTOR_BODY,
                 IF_BODY,
                 ELIF_BODY,
                 ELSE_BODY,
                 WHILE_BODY,
                 FOR_BODY -> EXECUTABLE_BODY;
            case LAMBDA_BODY -> LAMBDA_SUBTREE;
            case MATCH_SECTION_BODY -> MATCH_SUBTREE;
        };
    }

    /// Maps a callable scope kind to its explicit structural body policy.
    ///
    /// Function and constructor callables own executable bodies. Lambda callables remain deferred until their
    /// parameter, capture, and body inventory are implemented as one feature-owned surface. As with block scopes,
    /// the exhaustive switch prevents new callable kinds from receiving support by default.
    ///
    /// @param kind the callable scope kind whose structural support is being queried
    /// @return the policy for the callable's body/inventory boundary
    /// @throws NullPointerException if `kind` is `null`
    public static @NotNull FrontendBodySemanticSupportPolicy forCallableScopeKind(
            @NotNull CallableScopeKind kind
    ) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case FUNCTION_DECLARATION, CONSTRUCTOR_DECLARATION -> EXECUTABLE_BODY;
            case LAMBDA_EXPRESSION -> LAMBDA_SUBTREE;
        };
    }
}
