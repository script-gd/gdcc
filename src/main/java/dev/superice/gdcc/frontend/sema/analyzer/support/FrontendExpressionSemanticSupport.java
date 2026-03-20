package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/// Shared local expression-semantics helper used by both body-phase analyzers.
///
/// The helper stays analyzer-neutral:
/// - it returns pure semantic results
/// - it never publishes side tables
/// - it never emits diagnostics
/// - it delegates nested expression resolution back to the caller
///
/// `rootOwnsOutcome` tells the caller whether the returned non-success status belongs to the current
/// root expression itself, or is only propagated from an upstream dependency. The expression
/// analyzer uses this to decide whether it owns a frontend diagnostic at the root.
public final class FrontendExpressionSemanticSupport {
    @FunctionalInterface
    public interface NestedExpressionResolver {
        @NotNull FrontendExpressionType resolve(@NotNull Expression expression, boolean finalizeWindow);
    }

    public record ExpressionSemanticResult(
            @NotNull FrontendExpressionType expressionType,
            boolean rootOwnsOutcome
    ) {
        public ExpressionSemanticResult {
            Objects.requireNonNull(expressionType, "expressionType must not be null");
        }
    }

    private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull Supplier<ResolveRestriction> restrictionSupplier;
    private final @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext>
            propertyInitializerContextSupplier;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier;
    private final @NotNull FrontendSubscriptSemanticSupport subscriptSemanticSupport;

    public FrontendExpressionSemanticSupport(
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier
    ) {
        this(
                symbolBindings,
                scopesByAst,
                restrictionSupplier,
                () -> null,
                classRegistry,
                headReceiverSupportSupplier
        );
    }

    public FrontendExpressionSemanticSupport(
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext> propertyInitializerContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier
    ) {
        this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.restrictionSupplier = Objects.requireNonNull(restrictionSupplier, "restrictionSupplier must not be null");
        this.propertyInitializerContextSupplier = Objects.requireNonNull(
                propertyInitializerContextSupplier,
                "propertyInitializerContextSupplier must not be null"
        );
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.headReceiverSupportSupplier = Objects.requireNonNull(
                headReceiverSupportSupplier,
                "headReceiverSupportSupplier must not be null"
        );
        subscriptSemanticSupport = new FrontendSubscriptSemanticSupport(this.classRegistry);
    }

    public @NotNull ExpressionSemanticResult resolveLiteralExpressionType(
            @NotNull LiteralExpression literalExpression
    ) {
        var literalType = headReceiverSupportSupplier.get().resolveLiteralType(literalExpression);
        if (literalType != null) {
            return propagated(FrontendExpressionType.resolved(literalType));
        }
        return propagated(FrontendExpressionType.failed(
                "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
        ));
    }

    public @NotNull ExpressionSemanticResult resolveSelfExpressionType(@NotNull Node selfNode) {
        var boundaryDetail = FrontendPropertyInitializerSupport.detailForBindingBoundary(
                currentPropertyInitializerContext(),
                dev.superice.gdcc.frontend.sema.FrontendBindingKind.SELF,
                "self"
        );
        if (boundaryDetail != null) {
            return propagated(FrontendExpressionType.blocked(null, boundaryDetail));
        }
        return propagated(FrontendChainStatusBridge.toPublishedExpressionType(
                headReceiverSupportSupplier.get().resolveSelfReceiver(selfNode)
        ));
    }

    public @NotNull ExpressionSemanticResult resolveIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var binding = symbolBindings.get(identifierExpression);
        if (binding == null) {
            return propagated(FrontendExpressionType.failed(
                    "No published binding fact is available for identifier '" + identifierExpression.name() + "'"
            ));
        }
        var boundaryDetail = FrontendPropertyInitializerSupport.detailForBindingBoundary(
                currentPropertyInitializerContext(),
                binding.kind(),
                identifierExpression.name()
        );
        if (boundaryDetail != null) {
            return propagated(FrontendExpressionType.blocked(null, boundaryDetail));
        }
        return propagated(switch (binding.kind()) {
            case SELF -> resolveSelfExpressionType(identifierExpression).expressionType();
            case PARAMETER, LOCAL_VAR, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON, GLOBAL_ENUM ->
                    resolveValueIdentifierExpressionType(identifierExpression);
            case TYPE_META -> FrontendExpressionType.failed(
                    "Type-meta identifier '" + identifierExpression.name()
                            + "' cannot be consumed as an ordinary value; use a static route such as '"
                            + identifierExpression.name() + ".build(...)', '" + identifierExpression.name()
                            + ".new()', or a static constant access"
            );
            case METHOD, STATIC_METHOD, UTILITY_FUNCTION ->
                    resolveCallableIdentifierExpressionType(identifierExpression);
            case UNKNOWN, LITERAL -> FrontendExpressionType.failed(
                    "Identifier '" + identifierExpression.name() + "' does not resolve to a typed value"
            );
        });
    }

    /// Shared bare-call / direct-callable semantics.
    ///
    /// `resolveArgumentsWhenCalleeUnresolved` preserves the current analyzer-specific traversal
    /// contract:
    /// - expression typing still publishes argument expression facts even when a non-bare callee is
    ///   already non-resolved
    /// - chain-local dependency typing may stay conservative and stop early
    public @NotNull ExpressionSemanticResult resolveCallExpressionType(
            @NotNull CallExpression callExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveArgumentsWhenCalleeUnresolved,
            boolean finalizeWindow
    ) {
        if (callExpression.callee() instanceof IdentifierExpression bareCallee) {
            var calleeType = nestedResolver.resolve(bareCallee, finalizeWindow);
            if (calleeType.status() != FrontendExpressionTypeStatus.RESOLVED
                    && !shouldContinueBlockedBareCallResolution(bareCallee, calleeType)) {
                return propagated(calleeType);
            }
            var argumentResolution = resolveCallArgumentTypes(callExpression.arguments(), nestedResolver, finalizeWindow);
            if (argumentResolution.issue() != null) {
                return propagated(argumentResolution.issue());
            }
            return resolveBareIdentifierCallExpression(bareCallee, argumentResolution.argumentTypes());
        }

        var calleeType = nestedResolver.resolve(callExpression.callee(), finalizeWindow);
        if (!resolveArgumentsWhenCalleeUnresolved
                && calleeType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return propagated(calleeType);
        }

        var argumentResolution = resolveCallArgumentTypes(callExpression.arguments(), nestedResolver, finalizeWindow);
        if (argumentResolution.issue() != null) {
            return propagated(argumentResolution.issue());
        }
        if (calleeType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return propagated(calleeType);
        }
        return rootOutcome(calleeType.publishedType() instanceof GdCallableType
                ? FrontendExpressionType.unsupported(
                "Direct invocation of callable values is not implemented yet unless the callee is a bare identifier"
        )
                : FrontendExpressionType.failed("Call target does not resolve to a callable value"));
    }

    /// A blocked bare identifier callee may still be a valid bare-call winner:
    /// `FOUND_BLOCKED` preserves the shadowing overload set, so we should still run overload
    /// selection and keep the blocked call's real return type when the published binding is
    /// function-like. Other blocked callable values continue to short-circuit as propagated
    /// dependencies because they are not part of the bare-call overload route.
    private boolean shouldContinueBlockedBareCallResolution(
            @NotNull IdentifierExpression bareCallee,
            @NotNull FrontendExpressionType calleeType
    ) {
        if (calleeType.status() != FrontendExpressionTypeStatus.BLOCKED
                || !(calleeType.publishedType() instanceof GdCallableType)) {
            return false;
        }
        var binding = symbolBindings.get(Objects.requireNonNull(bareCallee, "bareCallee must not be null"));
        if (binding == null) {
            return false;
        }
        return switch (binding.kind()) {
            case METHOD, STATIC_METHOD, UTILITY_FUNCTION -> true;
            case SELF, LITERAL, LOCAL_VAR, PARAMETER, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON,
                 GLOBAL_ENUM, TYPE_META, UNKNOWN -> false;
        };
    }

    public @NotNull ExpressionSemanticResult resolveSubscriptExpressionType(
            @NotNull SubscriptExpression subscriptExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var baseType = nestedResolver.resolve(subscriptExpression.base(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(baseType);
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        var argumentResolution = resolveCallArgumentTypes(
                subscriptExpression.arguments(),
                nestedResolver,
                finalizeWindow
        );
        if (argumentResolution.issue() != null) {
            return propagated(argumentResolution.issue());
        }
        return rootOutcome(subscriptSemanticSupport.resolveSubscriptType(
                Objects.requireNonNull(baseType.publishedType(), "publishedType must not be null"),
                argumentResolution.argumentTypes(),
                "subscript expression"
        ));
    }

    public @NotNull ExpressionSemanticResult resolveLambdaExpressionType(
            @NotNull LambdaExpression lambdaExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            boolean finalizeWindow
    ) {
        var dependencyIssue = resolveNestedChildren
                ? firstNestedDependencyIssue(lambdaExpression, nestedResolver, finalizeWindow)
                : null;
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        return rootOutcome(FrontendExpressionType.unsupported(
                "Lambda expression typing is not supported by the current frontend expression-typing contract"
        ));
    }

    /// Unary operators are now part of the ordinary local expression contract:
    /// - unstable operand outcomes still propagate from upstream
    /// - exact `Variant` operands stay runtime-dynamic
    /// - exact resolved non-Variant operands use builtin operator metadata
    public @NotNull ExpressionSemanticResult resolveUnaryExpressionType(
            @NotNull UnaryExpression unaryExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var operandType = nestedResolver.resolve(unaryExpression.operand(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(operandType);
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        var publishedOperandType = Objects.requireNonNull(
                operandType.publishedType(),
                "publishedType must not be null for stable unary operand"
        );
        if (operandType.status() == FrontendExpressionTypeStatus.DYNAMIC
                || publishedOperandType instanceof GdVariantType) {
            return rootOutcome(FrontendExpressionType.dynamic(
                    "Variant operand routes unary operator '" + unaryExpression.operator()
                            + "' through runtime-dynamic semantics"
            ));
        }

        final GodotOperator operator;
        try {
            operator = GodotOperator.fromSourceLexeme(
                    unaryExpression.operator(),
                    GodotOperator.OperatorArity.UNARY
            );
        } catch (IllegalArgumentException ex) {
            return rootOutcome(FrontendExpressionType.failed(ex.getMessage()));
        }

        var returnType = resolveUnaryExactReturnType(operator, publishedOperandType);
        if (returnType != null) {
            return rootOutcome(FrontendExpressionType.resolved(returnType));
        }
        return rootOutcome(FrontendExpressionType.failed(
                "Unary operator '" + unaryExpression.operator()
                        + "' is not defined for operand type '" + publishedOperandType.getTypeName() + "'"
        ));
    }

    /// Exhaustive routing for the remaining explicitly deferred expression kinds.
    ///
    /// The analyzers intentionally keep dedicated entry points for the green paths such as
    /// identifiers, calls, subscript, assignment, lambda, and unary operators. Everything still
    /// outside that set is enumerated here so we no longer hide unsupported/deferred domains behind
    /// a generic fallback bucket.
    public @NotNull ExpressionSemanticResult resolveRemainingExplicitExpressionType(
            @NotNull Expression expression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            boolean finalizeWindow
    ) {
        return switch (Objects.requireNonNull(expression, "expression must not be null")) {
            case BinaryExpression binaryExpression -> resolveExplicitDeferredExpressionType(
                    binaryExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Binary operator typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case ConditionalExpression conditionalExpression -> resolveExplicitDeferredExpressionType(
                    conditionalExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Conditional expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case ArrayExpression arrayExpression -> resolveExplicitDeferredExpressionType(
                    arrayExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Array literal typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case DictionaryExpression dictionaryExpression -> resolveExplicitDeferredExpressionType(
                    dictionaryExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Dictionary literal typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case AwaitExpression awaitExpression -> resolveExplicitDeferredExpressionType(
                    awaitExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Await expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case PreloadExpression preloadExpression -> resolveExplicitDeferredExpressionType(
                    preloadExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Preload expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case GetNodeExpression getNodeExpression -> resolveExplicitDeferredExpressionType(
                    getNodeExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Get-node expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case CastExpression castExpression -> resolveExplicitDeferredExpressionType(
                    castExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Cast expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case TypeTestExpression typeTestExpression -> resolveExplicitDeferredExpressionType(
                    typeTestExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Type-test expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case PatternBindingExpression patternBindingExpression -> resolveExplicitDeferredExpressionType(
                    patternBindingExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Pattern binding expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case UnknownExpression unknownExpression -> rootOutcome(FrontendExpressionType.unsupported(
                    "Parser recovery expression '" + unknownExpression.nodeType()
                            + "' cannot participate in expression typing"
            ));
            case LiteralExpression _,
                 SelfExpression _,
                 IdentifierExpression _,
                 AttributeExpression _,
                 AssignmentExpression _,
                 CallExpression _,
                 SubscriptExpression _,
                 LambdaExpression _,
                 UnaryExpression _ -> throw new IllegalArgumentException(
                    "Expression kind '" + expression.getClass().getSimpleName()
                            + "' must use its dedicated semantic resolver"
            );
        };
    }

    /// Shared explicit-deferred path for recognized-but-not-yet-typed expressions.
    public @NotNull ExpressionSemanticResult resolveExplicitDeferredExpressionType(
            @NotNull Expression expression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            @NotNull String detailReason,
            boolean finalizeWindow
    ) {
        var dependencyIssue = resolveNestedChildren
                ? firstNestedDependencyIssue(expression, nestedResolver, finalizeWindow)
                : null;
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        return rootOutcome(FrontendExpressionType.deferred(detailReason));
    }

    private @NotNull FrontendExpressionType resolveValueIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = scopesByAst.get(identifierExpression);
        if (currentScope == null) {
            return FrontendExpressionType.unsupported(
                    "Identifier '" + identifierExpression.name() + "' is inside a skipped subtree"
            );
        }
        var valueResult = currentScope.resolveValue(identifierExpression.name(), currentRestriction());
        if (valueResult.isAllowed()) {
            return FrontendExpressionType.resolved(valueResult.requireValue().type());
        }
        if (valueResult.isBlocked()) {
            return FrontendExpressionType.blocked(
                    valueResult.requireValue().type(),
                    "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
            );
        }
        return FrontendExpressionType.failed(
                "Published value binding '" + identifierExpression.name() + "' is no longer visible"
        );
    }

    private @NotNull FrontendExpressionType resolveCallableIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = scopesByAst.get(identifierExpression);
        if (currentScope == null) {
            return FrontendExpressionType.unsupported(
                    "Callable expression '" + identifierExpression.name() + "' is inside a skipped subtree"
            );
        }
        var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction());
        var callableType = new GdCallableType();
        if (functionResult.isAllowed()) {
            return FrontendExpressionType.resolved(callableType);
        }
        if (functionResult.isBlocked()) {
            return FrontendExpressionType.blocked(
                    callableType,
                    "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
            );
        }
        return FrontendExpressionType.failed(
                "Published callable binding '" + identifierExpression.name() + "' is no longer visible"
        );
    }

    private @NotNull CallArgumentResolution resolveCallArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var argumentTypes = new ArrayList<GdType>(arguments.size());
        for (var argument : arguments) {
            var argumentType = nestedResolver.resolve(argument, finalizeWindow);
            switch (argumentType.status()) {
                case RESOLVED, DYNAMIC -> argumentTypes.add(
                        Objects.requireNonNull(argumentType.publishedType(), "publishedType must not be null")
                );
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> {
                    return new CallArgumentResolution(List.of(), argumentType);
                }
            }
        }
        return new CallArgumentResolution(List.copyOf(argumentTypes), null);
    }

    private @NotNull ExpressionSemanticResult resolveBareIdentifierCallExpression(
            @NotNull IdentifierExpression bareCallee,
            @NotNull List<GdType> argumentTypes
    ) {
        var currentScope = scopesByAst.get(bareCallee);
        if (currentScope == null) {
            return rootOutcome(FrontendExpressionType.unsupported(
                    "Bare call '" + bareCallee.name() + "(...)' is inside a skipped subtree"
            ));
        }
        var functionResult = currentScope.resolveFunctions(bareCallee.name(), currentRestriction());
        if (functionResult.isAllowed()) {
            var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
            if (overloadSelection.selected() != null) {
                return rootOutcome(FrontendExpressionType.resolved(
                        overloadSelection.selected().getReturnType()
                ));
            }
            return rootOutcome(FrontendExpressionType.failed(
                    Objects.requireNonNull(overloadSelection.detailReason(), "detailReason must not be null")
            ));
        }
        if (functionResult.isBlocked()) {
            var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
            var blockedReturnType = overloadSelection.selected() != null
                    ? overloadSelection.selected().getReturnType()
                    : null;
            return rootOutcome(FrontendExpressionType.blocked(
                    blockedReturnType,
                    "Binding '" + bareCallee.name() + "' is not accessible in the current context"
            ));
        }
        return rootOutcome(FrontendExpressionType.failed(
                "Published bare callee binding '" + bareCallee.name() + "' is no longer visible"
        ));
    }

    @NotNull CallableOverloadSelection selectCallableOverload(
            @NotNull List<? extends FunctionDef> overloadSet,
            @NotNull List<GdType> argumentTypes
    ) {
        var applicable = overloadSet.stream()
                .filter(callable -> matchesCallableArguments(callable, argumentTypes))
                .toList();
        if (applicable.size() == 1) {
            return new CallableOverloadSelection(applicable.getFirst(), null);
        }
        if (applicable.size() > 1) {
            return new CallableOverloadSelection(
                    null,
                    "Ambiguous bare call overload: " + renderCallableSignatures(applicable)
            );
        }
        var detailReason = overloadSet.isEmpty()
                ? "Bare call resolves to an empty overload set"
                : "No applicable overload for bare call: "
                + buildCallableMismatchReason(overloadSet.getFirst(), argumentTypes)
                + ". candidates: " + renderCallableSignatures(overloadSet);
        return new CallableOverloadSelection(null, detailReason);
    }

    private boolean matchesCallableArguments(
            @NotNull FunctionDef callable,
            @NotNull List<GdType> argumentTypes
    ) {
        var parameters = List.copyOf(callable.getParameters());
        var fixedCount = parameters.size();
        var providedCount = argumentTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
            return false;
        }
        if (!callable.isVararg() && providedCount > fixedCount) {
            return false;
        }
        var fixedPrefixCount = Math.min(providedCount, fixedCount);
        for (var index = 0; index < fixedPrefixCount; index++) {
            if (!classRegistry.checkAssignable(argumentTypes.get(index), parameters.get(index).getType())) {
                return false;
            }
        }
        if (!callable.isVararg()) {
            return true;
        }
        // GDScript vararg tails are packaged as Variant at the call boundary, so any already-typed
        // runtime value may flow into the tail without proving a strict `T -> Variant` conversion.
        return true;
    }

    private @NotNull String buildCallableMismatchReason(
            @NotNull FunctionDef callable,
            @NotNull List<GdType> argumentTypes
    ) {
        var parameters = List.copyOf(callable.getParameters());
        var fixedCount = parameters.size();
        var providedCount = argumentTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
            var missingParameterIndex = firstMissingRequiredParameter(parameters, providedCount);
            return "missing required parameter #" + (missingParameterIndex + 1) + " ('"
                    + parameters.get(missingParameterIndex).getName() + "')";
        }
        if (!callable.isVararg() && providedCount > fixedCount) {
            return "expected " + fixedCount + " arguments, got " + providedCount;
        }
        var fixedPrefixCount = Math.min(providedCount, fixedCount);
        for (var index = 0; index < fixedPrefixCount; index++) {
            var argumentType = argumentTypes.get(index);
            var parameter = parameters.get(index);
            if (!classRegistry.checkAssignable(argumentType, parameter.getType())) {
                return "argument #" + (index + 1) + " of type '" + argumentType.getTypeName()
                        + "' is not assignable to parameter '" + parameter.getName()
                        + "' of type '" + parameter.getType().getTypeName() + "'";
            }
        }
        return "no compatible signature found";
    }

    private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext() {
        return propertyInitializerContextSupplier.get();
    }

    /// Typed containers keep richer source-level names such as `Array[int]`, but unary metadata is
    /// still owned by the raw builtin classes.
    private @Nullable GdType resolveUnaryExactReturnType(
            @NotNull GodotOperator operator,
            @NotNull GdType operandType
    ) {
        var builtinClass = findUnaryOperatorOwnerClass(operandType);
        if (builtinClass == null) {
            return null;
        }
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            if (!normalizeTypeName(classOperator.rightType()).isEmpty()) {
                continue;
            }
            var returnType = parseOperatorReturnType(classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    private @Nullable ExtensionBuiltinClass findUnaryOperatorOwnerClass(@NotNull GdType operandType) {
        var directBuiltinClass = classRegistry.findBuiltinClass(operandType.getTypeName());
        if (directBuiltinClass != null) {
            return directBuiltinClass;
        }
        if (operandType instanceof GdArrayType) {
            return classRegistry.findBuiltinClass("Array");
        }
        if (operandType instanceof GdDictionaryType) {
            return classRegistry.findBuiltinClass("Dictionary");
        }
        return null;
    }

    private @Nullable GdType parseOperatorReturnType(@NotNull ExtensionBuiltinClass.ClassOperator classOperator) {
        var returnTypeName = normalizeTypeName(classOperator.returnType());
        if (returnTypeName.isEmpty()) {
            return null;
        }
        return classRegistry.tryResolveDeclaredType(returnTypeName);
    }

    private static @NotNull String normalizeTypeName(@Nullable String typeName) {
        return typeName == null ? "" : typeName.trim();
    }

    private boolean canOmitTrailingParameters(
            @NotNull List<? extends ParameterDef> parameters,
            int providedCount
    ) {
        for (var index = providedCount; index < parameters.size(); index++) {
            if (parameters.get(index).getDefaultValueFunc() == null) {
                return false;
            }
        }
        return true;
    }

    private int firstMissingRequiredParameter(
            @NotNull List<? extends ParameterDef> parameters,
            int providedCount
    ) {
        for (var index = providedCount; index < parameters.size(); index++) {
            if (parameters.get(index).getDefaultValueFunc() == null) {
                return index;
            }
        }
        return providedCount;
    }

    private @NotNull String renderCallableSignatures(@NotNull List<? extends FunctionDef> callables) {
        var signatures = new ArrayList<String>(callables.size());
        for (var callable : callables) {
            var args = new ArrayList<String>();
            for (var parameter : callable.getParameters()) {
                args.add(parameter.getType().getTypeName());
            }
            if (callable.isVararg()) {
                args.add("...");
            }
            signatures.add(callable.getName() + "(" + String.join(", ", args) + ")");
        }
        return String.join("; ", signatures);
    }

    private @Nullable FrontendExpressionType firstNestedDependencyIssue(
            @NotNull Node node,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        for (var child : node.getChildren()) {
            if (child instanceof Expression childExpression) {
                var dependencyIssue = firstNonResolvedDependency(nestedResolver.resolve(childExpression, finalizeWindow));
                if (dependencyIssue != null) {
                    return dependencyIssue;
                }
                continue;
            }
            var nestedIssue = firstNestedDependencyIssue(child, nestedResolver, finalizeWindow);
            if (nestedIssue != null) {
                return nestedIssue;
            }
        }
        return null;
    }

    private @Nullable FrontendExpressionType firstNonResolvedDependency(
            @Nullable FrontendExpressionType... dependencies
    ) {
        for (var dependency : dependencies) {
            if (dependency == null
                    || dependency.status() == FrontendExpressionTypeStatus.RESOLVED
                    || dependency.status() == FrontendExpressionTypeStatus.DYNAMIC) {
                continue;
            }
            return dependency;
        }
        return null;
    }

    private @NotNull ResolveRestriction currentRestriction() {
        return Objects.requireNonNull(restrictionSupplier.get(), "currentRestriction must not be null");
    }

    private static @NotNull ExpressionSemanticResult propagated(@NotNull FrontendExpressionType expressionType) {
        return new ExpressionSemanticResult(expressionType, false);
    }

    private static @NotNull ExpressionSemanticResult rootOutcome(@NotNull FrontendExpressionType expressionType) {
        return new ExpressionSemanticResult(expressionType, true);
    }

    private record CallArgumentResolution(
            @NotNull List<GdType> argumentTypes,
            @Nullable FrontendExpressionType issue
    ) {
        private CallArgumentResolution {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }

    record CallableOverloadSelection(
            @Nullable FunctionDef selected,
            @Nullable String detailReason
    ) {
    }
}
