package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdNodePathType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared analyzer-side support for chain heads and local atomic receiver facts.
///
/// Why this helper exists:
/// - both `FrontendChainBindingAnalyzer` and `FrontendExprTypeAnalyzer` need the same rules for
///   turning a chain head into `ReceiverState`
/// - those rules are not the chain-reduction core itself; they are the glue that bridges already
///   published binding/scope facts into a stable receiver model
/// - keeping this logic in one place reduces the risk that `self`, `TYPE_META`, literal heads, or
///   blocked value bindings drift apart between the two analyzers
///
/// What this helper does:
/// - consumes already-published `symbolBindings()` and `scopesByAst()` facts
/// - resolves identifier, `self`, and literal heads into `ReceiverState`
/// - delegates nested-chain heads and non-atomic fallback expressions through injected callbacks
///
/// What this helper deliberately does not do:
/// - it does not run step-by-step chain reduction
/// - it does not publish side-table entries
/// - it does not emit diagnostics
/// - it does not own any phase-specific caching policy
///
/// Typical usage from an analyzer:
/// - inject one callback for nested `AttributeExpression` bases so the analyzer can reuse its own
///   reduction cache and published result policy
/// - inject one callback for all other fallback expressions so the analyzer can decide whether to
///   convert a local expression-typing result into a receiver, or to defer/skip that path
/// - call `resolveHeadReceiver(...)` when a chain reduction needs its initial receiver
public final class FrontendChainHeadReceiverSupport {
    /// Resolves nested `a.b.c`-style base expressions.
    ///
    /// The helper does not know whether the caller wants to reuse a reduction cache, publish
    /// diagnostics, or simply read a previously reduced chain. That policy stays with the analyzer,
    /// so nested attribute handling is injected instead of hardcoded here.
    @FunctionalInterface
    public interface NestedAttributeReceiverResolver {
        @Nullable FrontendChainReductionHelper.ReceiverState resolve(@NotNull AttributeExpression attributeExpression);
    }

    /// Resolves every non-atomic fallback expression that is not a nested attribute chain.
    ///
    /// This is typically used for cases like call expressions or other expression kinds where the
    /// analyzer wants to ask its local expression-type support for a type first, then convert that
    /// type into a receiver. Keeping that conversion outside this helper avoids coupling it to one
    /// specific analyzer's expression-typing strategy.
    @FunctionalInterface
    public interface FallbackExpressionReceiverResolver {
        @Nullable FrontendChainReductionHelper.ReceiverState resolve(@NotNull Expression expression);
    }

    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull ResolveRestriction currentRestriction;
    private final boolean staticContext;
    private final @NotNull NestedAttributeReceiverResolver nestedAttributeReceiverResolver;
    private final @NotNull FallbackExpressionReceiverResolver fallbackExpressionReceiverResolver;

    /// Creates a support object bound to the caller's current semantic context.
    ///
    /// The constructor arguments intentionally mirror the minimal inputs that may change while an
    /// analyzer walks the AST:
    /// - `analysisData` gives access to already published binding facts
    /// - `scopesByAst` and `currentRestriction` drive current-scope lookup
    /// - `staticContext` controls policies such as `self` becoming `BLOCKED`
    /// - the two callbacks let analyzers plug in their own nested-chain and fallback-expression
    ///   strategies without duplicating the atomic head rules
    public FrontendChainHeadReceiverSupport(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull ResolveRestriction currentRestriction,
            boolean staticContext,
            @NotNull NestedAttributeReceiverResolver nestedAttributeReceiverResolver,
            @NotNull FallbackExpressionReceiverResolver fallbackExpressionReceiverResolver
    ) {
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.currentRestriction = Objects.requireNonNull(currentRestriction, "currentRestriction must not be null");
        this.staticContext = staticContext;
        this.nestedAttributeReceiverResolver = Objects.requireNonNull(
                nestedAttributeReceiverResolver,
                "nestedAttributeReceiverResolver must not be null"
        );
        this.fallbackExpressionReceiverResolver = Objects.requireNonNull(
                fallbackExpressionReceiverResolver,
                "fallbackExpressionReceiverResolver must not be null"
        );
    }

    /// Resolves the base of a chain into its initial receiver state.
    ///
    /// This is the main entry point used by analyzers before they hand control to
    /// `FrontendChainReductionHelper`. The dispatch order is intentional:
    /// - identifiers first consume published binding facts
    /// - `self` and literals use local atomic rules
    /// - nested attributes are delegated back to the analyzer so cached reduction can be reused
    /// - everything else is delegated to the analyzer's fallback-expression policy
    ///
    /// Returning `null` means only that a caller-supplied nested/fallback callback declined to
    /// publish any receiver fact. Supported identifier-head failures are materialized as `FAILED`
    /// receiver states instead of being silently compressed into `null`.
    public @Nullable FrontendChainReductionHelper.ReceiverState resolveHeadReceiver(@NotNull Expression base) {
        return switch (Objects.requireNonNull(base, "base must not be null")) {
            case IdentifierExpression identifierExpression -> resolveIdentifierHeadReceiver(identifierExpression);
            case SelfExpression selfExpression -> resolveSelfReceiver(selfExpression);
            case LiteralExpression literalExpression -> toResolvedLiteralReceiver(literalExpression);
            case AttributeExpression attributeExpression ->
                    nestedAttributeReceiverResolver.resolve(attributeExpression);
            default -> fallbackExpressionReceiverResolver.resolve(base);
        };
    }

    /// Resolves an identifier that appears as a chain head.
    ///
    /// The helper reads `symbolBindings()` first instead of directly probing scopes, because the
    /// published binding kind tells us which namespace already won during top binding. That keeps the
    /// body-phase contract stable: `TYPE_META`, ordinary values, `self`, and "not a value receiver"
    /// cases all follow the published result instead of re-running ad-hoc namespace competition.
    ///
    /// Unlike `resolveHeadReceiver(...)`, identifier heads never use `null` as a miss sentinel:
    /// supported head failures are always materialized as `FAILED` receiver states so downstream
    /// reduction preserves provenance instead of collapsing into an empty chain result.
    public @NotNull FrontendChainReductionHelper.ReceiverState resolveIdentifierHeadReceiver(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var identifier = Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        var binding = analysisData.symbolBindings().get(identifier);
        if (binding == null) {
            return failedHeadReceiver(
                    identifier,
                    "No published chain-head binding fact is available for identifier '" + identifier.name() + "'"
            );
        }
        return switch (binding.kind()) {
            case TYPE_META -> resolveTypeMetaReceiver(identifier);
            case SELF -> resolveSelfReceiver(identifier);
            case PARAMETER, LOCAL_VAR, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON, GLOBAL_ENUM ->
                    resolveValueReceiver(identifier);
            case METHOD, STATIC_METHOD, UTILITY_FUNCTION -> resolveCallableReceiver(identifier);
            case UNKNOWN -> failedHeadReceiver(
                    identifier,
                    "Chain head '" + identifier.name() + "' does not resolve to a published value or type-meta receiver"
            );
            case LITERAL -> failedHeadReceiver(
                    identifier,
                    "Chain head '" + identifier.name() + "' does not publish a value receiver"
            );
        };
    }

    /// Resolves a published `TYPE_META` identifier into a type-meta receiver.
    ///
    /// This path is used for class-style chain heads such as `Worker.build()` or `Vector3.BACK`.
    /// It re-checks the current lexical scope because the helper is consuming published facts, not
    /// owning them: if the AST node is inside a skipped subtree we publish `UNSUPPORTED`; if the
    /// previously published type-meta winner is no longer reachable, we publish `FAILED`.
    public @NotNull FrontendChainReductionHelper.ReceiverState resolveTypeMetaReceiver(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var identifier = Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        var currentScope = scopesByAst.get(identifier);
        if (currentScope == null) {
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.UNSUPPORTED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Type-meta receiver '" + identifier.name() + "' is inside a skipped subtree"
            );
        }
        var typeMetaResult = currentScope.resolveTypeMeta(identifier.name(), currentRestriction);
        if (!typeMetaResult.isAllowed()) {
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.FAILED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Published type-meta receiver '" + identifier.name() + "' is no longer visible"
            );
        }
        return FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(typeMetaResult.requireValue());
    }

    /// Resolves a published ordinary value identifier into an instance receiver.
    ///
    /// The result preserves the important distinction between:
    /// - `RESOLVED`: a usable value receiver exists
    /// - `BLOCKED`: a winner exists, but the current restriction forbids its use here
    /// - `UNSUPPORTED`: the subtree has no stable scope fact
    /// - `FAILED`: the previously published winner can no longer be recovered from scope lookup
    public @NotNull FrontendChainReductionHelper.ReceiverState resolveValueReceiver(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var identifier = Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        var currentScope = scopesByAst.get(identifier);
        if (currentScope == null) {
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.UNSUPPORTED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Value receiver '" + identifier.name() + "' is inside a skipped subtree"
            );
        }
        var valueResult = currentScope.resolveValue(identifier.name(), currentRestriction);
        if (valueResult.isAllowed()) {
            return FrontendChainReductionHelper.ReceiverState.resolvedInstance(valueResult.requireValue().type());
        }
        if (valueResult.isBlocked()) {
            var winner = valueResult.requireValue();
            return FrontendChainReductionHelper.ReceiverState.blockedFrom(
                    FrontendChainReductionHelper.ReceiverState.resolvedInstance(winner.type()),
                    "Binding '" + identifier.name() + "' is not accessible in the current context"
            );
        }
        return new FrontendChainReductionHelper.ReceiverState(
                FrontendChainReductionHelper.Status.FAILED,
                FrontendReceiverKind.UNKNOWN,
                null,
                null,
                "Published value receiver '" + identifier.name() + "' is no longer visible"
        );
    }

    /// Resolves a published bare callable symbol into a `Callable` value receiver.
    ///
    /// Function namespace lookup keeps the same blocked-hit semantics as ordinary value lookup:
    /// a blocked local winner still shadows outer/global functions, so we preserve it as a blocked
    /// `Callable` receiver instead of pretending the name disappeared.
    public @NotNull FrontendChainReductionHelper.ReceiverState resolveCallableReceiver(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var identifier = Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        var currentScope = scopesByAst.get(identifier);
        if (currentScope == null) {
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.UNSUPPORTED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Callable receiver '" + identifier.name() + "' is inside a skipped subtree"
            );
        }
        var functionResult = currentScope.resolveFunctions(identifier.name(), currentRestriction);
        var callableType = new GdCallableType();
        if (functionResult.isAllowed()) {
            return FrontendChainReductionHelper.ReceiverState.resolvedInstance(callableType);
        }
        if (functionResult.isBlocked()) {
            return FrontendChainReductionHelper.ReceiverState.blockedFrom(
                    FrontendChainReductionHelper.ReceiverState.resolvedInstance(callableType),
                    "Binding '" + identifier.name() + "' is not accessible in the current context"
            );
        }
        return new FrontendChainReductionHelper.ReceiverState(
                FrontendChainReductionHelper.Status.FAILED,
                FrontendReceiverKind.UNKNOWN,
                null,
                null,
                "Published callable receiver '" + identifier.name() + "' is no longer visible"
        );
    }

    /// Resolves `self` for the current AST location.
    ///
    /// `self` is not modeled as an ordinary scope value, so the helper walks outward to the nearest
    /// enclosing `ClassScope` and synthesizes the receiver from the owning class type. This method
    /// also centralizes the static-context policy: when the caller says the current context is
    /// static, the same receiver is preserved but wrapped as `BLOCKED`.
    public @NotNull FrontendChainReductionHelper.ReceiverState resolveSelfReceiver(@NotNull Node selfNode) {
        var classScope = findEnclosingClassScope(scopesByAst.get(Objects.requireNonNull(selfNode, "selfNode must not be null")));
        if (classScope == null) {
            return new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.UNSUPPORTED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    "Keyword 'self' is inside a skipped subtree"
            );
        }
        var resolvedSelf = FrontendChainReductionHelper.ReceiverState.resolvedInstance(
                new GdObjectType(classScope.getCurrentClass().getName())
        );
        if (staticContext) {
            return FrontendChainReductionHelper.ReceiverState.blockedFrom(
                    resolvedSelf,
                    "Keyword 'self' is not available in static context"
            );
        }
        return resolvedSelf;
    }

    /// Resolves the local type rule for a literal expression.
    ///
    /// This method is intentionally public because both analyzers need the same atomic literal
    /// typing rule even outside full chain-head resolution. Returning `null` means the literal kind
    /// is currently outside the helper's local rule set and the caller must decide whether that
    /// becomes `FAILED`, `DEFERRED`, or another higher-level outcome.
    public @Nullable GdType resolveLiteralType(@NotNull LiteralExpression literalExpression) {
        return switch (Objects.requireNonNull(literalExpression, "literalExpression must not be null").kind()) {
            case "integer" -> GdIntType.INT;
            case "float" -> GdFloatType.FLOAT;
            case "string" -> GdStringType.STRING;
            case "string_name" -> GdStringNameType.STRING_NAME;
            case "true", "false" -> GdBoolType.BOOL;
            case "null" -> GdNilType.NIL;
            case "node_path" -> GdNodePathType.NODE_PATH;
            case "number" -> literalExpression.sourceText().contains(".")
                    ? GdFloatType.FLOAT
                    : GdIntType.INT;
            default -> null;
        };
    }

    /// Converts a literal head directly into receiver space.
    ///
    /// This is kept private because callers that only need the literal type should go through
    /// `resolveLiteralType(...)`, while chain-head reduction should use `resolveHeadReceiver(...)`.
    private @NotNull FrontendChainReductionHelper.ReceiverState toResolvedLiteralReceiver(
            @NotNull LiteralExpression literalExpression
    ) {
        var literalType = resolveLiteralType(literalExpression);
        if (literalType != null) {
            return FrontendChainReductionHelper.ReceiverState.resolvedInstance(literalType);
        }
        return new FrontendChainReductionHelper.ReceiverState(
                FrontendChainReductionHelper.Status.FAILED,
                FrontendReceiverKind.UNKNOWN,
                null,
                null,
                "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
        );
    }

    private static @NotNull FrontendChainReductionHelper.ReceiverState failedHeadReceiver(
            @NotNull IdentifierExpression identifierExpression,
            @NotNull String detailReason
    ) {
        Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        return new FrontendChainReductionHelper.ReceiverState(
                FrontendChainReductionHelper.Status.FAILED,
                FrontendReceiverKind.UNKNOWN,
                null,
                null,
                Objects.requireNonNull(detailReason, "detailReason must not be null")
        );
    }

    /// Finds the nearest enclosing class boundary for `self` reconstruction.
    ///
    /// The walk is lexical and intentionally small: the helper only needs the owning class type, not
    /// any broader analyzer state. Keeping this search local avoids duplicating the same "find class
    /// scope up the parent chain" logic in every analyzer.
    private static @Nullable ClassScope findEnclosingClassScope(@Nullable Scope scope) {
        var current = scope;
        while (current != null) {
            if (current instanceof ClassScope classScope) {
                return classScope;
            }
            current = current.getParentScope();
        }
        return null;
    }
}
