package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.util.StringUtil;
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
            boolean rootOwnsOutcome,
            @Nullable FrontendResolvedCall publishedCallOrNull
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
            var bareBinding = symbolBindings.get(bareCallee);
            if (bareBinding != null && bareBinding.kind() == FrontendBindingKind.TYPE_META) {
                var argumentResolution = resolveCallArgumentTypes(callExpression.arguments(), nestedResolver, finalizeWindow);
                if (argumentResolution.issue() != null) {
                    return propagated(argumentResolution.issue());
                }
                return resolveBareTypeMetaConstructorCallExpression(bareCallee, argumentResolution.argumentTypes());
            }
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

    /// Bare builtin direct constructors are the one intentional exception to the ordinary
    /// "type-meta is not a value" rule used by identifier typing.
    ///
    /// This path keeps object construction strict:
    /// - builtin types may use `Vector3i(...)`, `Array(...)`, ...
    /// - object types must continue to use `TypeName.new(...)`
    private @NotNull ExpressionSemanticResult resolveBareTypeMetaConstructorCallExpression(
            @NotNull IdentifierExpression bareCallee,
            @NotNull List<GdType> argumentTypes
    ) {
        var receiverState = headReceiverSupportSupplier.get().resolveTypeMetaReceiver(bareCallee);
        if (receiverState.status() != FrontendChainReductionHelper.Status.RESOLVED) {
            return rootOutcome(switch (receiverState.status()) {
                case UNSUPPORTED -> FrontendExpressionType.unsupported(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
                case FAILED, BLOCKED, DEFERRED, DYNAMIC -> FrontendExpressionType.failed(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
                case RESOLVED ->
                        throw new IllegalStateException("resolved receiver state unexpectedly missing type-meta metadata");
            });
        }
        var receiverTypeMeta = Objects.requireNonNull(
                receiverState.receiverTypeMeta(),
                "resolved type-meta constructor call must carry receiverTypeMeta"
        );
        if (receiverTypeMeta.kind() != dev.superice.gdcc.scope.ScopeTypeMetaKind.BUILTIN) {
            return rootOutcome(FrontendExpressionType.failed(
                    "Type-meta bare call '" + bareCallee.name()
                            + "(...)' is not a builtin direct constructor; use '" + bareCallee.name()
                            + ".new(...)' or a static route instead"
            ));
        }

        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(classRegistry, receiverTypeMeta, argumentTypes);
        return switch (resolution.status()) {
            case RESOLVED -> rootOutcome(
                    FrontendExpressionType.resolved(receiverTypeMeta.instanceType()),
                    resolvedBareConstructorCall(bareCallee, receiverTypeMeta, argumentTypes, resolution)
            );
            case FAILED -> rootOutcome(
                    FrontendExpressionType.failed(
                            Objects.requireNonNull(resolution.detailReason(), "detailReason must not be null")
                    ),
                    failedBareConstructorCall(bareCallee, receiverTypeMeta, argumentTypes, resolution)
            );
            default ->
                    throw new IllegalStateException("unexpected bare constructor resolution status: " + resolution.status());
        };
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

    /// Binary operators split into two layers:
    /// - source-level special rules (`and/or`, typed `Array[T] + Array[T]`, explicit `not in` boundary)
    /// - ordinary builtin metadata lookup for the remaining exact pairs
    public @NotNull ExpressionSemanticResult resolveBinaryExpressionType(
            @NotNull BinaryExpression binaryExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var leftOperandType = nestedResolver.resolve(binaryExpression.left(), finalizeWindow);
        var leftDependencyIssue = firstNonResolvedDependency(leftOperandType);
        if (leftDependencyIssue != null) {
            return propagated(leftDependencyIssue);
        }

        var rightOperandType = nestedResolver.resolve(binaryExpression.right(), finalizeWindow);
        var rightDependencyIssue = firstNonResolvedDependency(rightOperandType);
        if (rightDependencyIssue != null) {
            return propagated(rightDependencyIssue);
        }

        return rootOutcome(resolveBinaryOperatorResultType(
                classRegistry,
                binaryExpression.operator(),
                leftOperandType,
                rightOperandType
        ));
    }

    /// Shared binary-operator typing entry used by ordinary `BinaryExpression` roots and compound
    /// assignment semantic checks.
    ///
    /// Callers must pass only stable operands (`RESOLVED` / `DYNAMIC`) whose published type is already
    /// available. Dependency propagation stays outside this helper so the owner can keep precise
    /// root-vs-propagated diagnostic ownership.
    public static @NotNull FrontendExpressionType resolveBinaryOperatorResultType(
            @NotNull ClassRegistry classRegistry,
            @NotNull String operatorText,
            @NotNull FrontendExpressionType leftOperandType,
            @NotNull FrontendExpressionType rightOperandType
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var actualOperatorText = Objects.requireNonNull(operatorText, "operatorText must not be null");
        var publishedLeftType = requireStableOperatorOperandType("leftOperandType", leftOperandType);
        var publishedRightType = requireStableOperatorOperandType("rightOperandType", rightOperandType);

        if ("not in".equals(actualOperatorText)) {
            return FrontendExpressionType.unsupported(
                    "Binary operator 'not in' is recognized but still uses an explicit unsupported boundary; "
                            + "it must not be silently normalized to 'in'"
            );
        }

        final GodotOperator operator;
        try {
            operator = GodotOperator.fromSourceLexeme(actualOperatorText, GodotOperator.OperatorArity.BINARY);
        } catch (IllegalArgumentException ex) {
            return FrontendExpressionType.failed(ex.getMessage());
        }

        var specialReturnType = resolveBinarySpecialReturnType(operator, publishedLeftType, publishedRightType);
        if (specialReturnType != null) {
            return FrontendExpressionType.resolved(specialReturnType);
        }

        if (isRuntimeOpenOperatorOperand(leftOperandType, publishedLeftType)
                || isRuntimeOpenOperatorOperand(rightOperandType, publishedRightType)) {
            return FrontendExpressionType.dynamic(
                    "Runtime-open operand routes binary operator '" + actualOperatorText
                            + "' through Variant semantics"
            );
        }

        var exactReturnType = resolveBinaryExactReturnType(
                classRegistry,
                operator,
                publishedLeftType,
                publishedRightType
        );
        if (exactReturnType != null) {
            return FrontendExpressionType.resolved(exactReturnType);
        }
        return FrontendExpressionType.failed(
                "Binary operator '" + actualOperatorText
                        + "' is not defined for operand types '" + publishedLeftType.getTypeName()
                        + "' and '" + publishedRightType.getTypeName() + "'"
        );
    }

    /// Exhaustive routing for the remaining explicitly deferred expression kinds.
    ///
    /// The analyzers intentionally keep dedicated entry points for the green paths such as
    /// identifiers, calls, subscript, assignment, lambda, unary operators, and binary operators.
    /// Everything still
    /// outside that set is enumerated here so we no longer hide unsupported/deferred domains behind
    /// a generic fallback bucket.
    public @NotNull ExpressionSemanticResult resolveRemainingExplicitExpressionType(
            @NotNull Expression expression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            boolean finalizeWindow
    ) {
        return switch (Objects.requireNonNull(expression, "expression must not be null")) {
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
                 UnaryExpression _,
                 BinaryExpression _ -> throw new IllegalArgumentException(
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
        var bareCallRoute = bareCallRoute(bareCallee);
        var functionResult = currentScope.resolveFunctions(bareCallee.name(), currentRestriction());
        if (functionResult.isAllowed()) {
            var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
            if (overloadSelection.selected() != null) {
                var selected = overloadSelection.selected();
                return rootOutcome(
                        FrontendExpressionType.resolved(selected.getReturnType()),
                        resolvedBareCall(bareCallee, bareCallRoute, selected, argumentTypes)
                );
            }
            var detailReason = Objects.requireNonNull(overloadSelection.detailReason(), "detailReason must not be null");
            return rootOutcome(
                    FrontendExpressionType.failed(detailReason),
                    failedBareCall(bareCallee, bareCallRoute, argumentTypes, detailReason)
            );
        }
        if (functionResult.isBlocked()) {
            var overloadSelection = selectCallableOverload(functionResult.requireValue(), argumentTypes);
            var selected = overloadSelection.selected();
            var blockedReturnType = selected != null
                    ? selected.getReturnType()
                    : null;
            var detailReason = "Binding '" + bareCallee.name() + "' is not accessible in the current context";
            return rootOutcome(
                    FrontendExpressionType.blocked(blockedReturnType, detailReason),
                    selected != null ? blockedBareCall(bareCallee, bareCallRoute, selected, argumentTypes, detailReason) : null
            );
        }
        var detailReason = "Published bare callee binding '" + bareCallee.name() + "' is no longer visible";
        return rootOutcome(
                FrontendExpressionType.failed(detailReason),
                failedBareCall(bareCallee, bareCallRoute, argumentTypes, detailReason)
        );
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

    private @NotNull BareCallRoute bareCallRoute(@NotNull IdentifierExpression bareCallee) {
        var binding = symbolBindings.get(Objects.requireNonNull(bareCallee, "bareCallee must not be null"));
        var receiverType = currentClassReceiverType(bareCallee);
        if (binding == null) {
            return new BareCallRoute(FrontendCallResolutionKind.UNKNOWN, FrontendReceiverKind.UNKNOWN, receiverType);
        }
        return switch (binding.kind()) {
            case METHOD -> new BareCallRoute(
                    FrontendCallResolutionKind.INSTANCE_METHOD,
                    FrontendReceiverKind.INSTANCE,
                    receiverType
            );
            case STATIC_METHOD -> new BareCallRoute(
                    FrontendCallResolutionKind.STATIC_METHOD,
                    FrontendReceiverKind.TYPE_META,
                    receiverType
            );
            case UTILITY_FUNCTION -> new BareCallRoute(
                    FrontendCallResolutionKind.STATIC_METHOD,
                    FrontendReceiverKind.TYPE_META,
                    null
            );
            default ->
                    new BareCallRoute(FrontendCallResolutionKind.UNKNOWN, FrontendReceiverKind.UNKNOWN, receiverType);
        };
    }

    private @NotNull FrontendResolvedCall resolvedBareCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull BareCallRoute bareCallRoute,
            @NotNull FunctionDef selected,
            @NotNull List<GdType> argumentTypes
    ) {
        return FrontendResolvedCall.resolved(
                bareCallee.name(),
                bareCallRoute.callKind(),
                bareCallRoute.receiverKind(),
                ownerKind(selected),
                bareCallRoute.receiverType(),
                selected.getReturnType(),
                argumentTypes,
                selected
        );
    }

    private @NotNull FrontendResolvedCall blockedBareCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull BareCallRoute bareCallRoute,
            @NotNull FunctionDef selected,
            @NotNull List<GdType> argumentTypes,
            @NotNull String detailReason
    ) {
        return FrontendResolvedCall.blocked(
                bareCallee.name(),
                bareCallRoute.callKind(),
                bareCallRoute.receiverKind(),
                ownerKind(selected),
                bareCallRoute.receiverType(),
                selected.getReturnType(),
                argumentTypes,
                selected,
                detailReason
        );
    }

    private @NotNull FrontendResolvedCall failedBareCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull BareCallRoute bareCallRoute,
            @NotNull List<GdType> argumentTypes,
            @NotNull String detailReason
    ) {
        return FrontendResolvedCall.failed(
                bareCallee.name(),
                bareCallRoute.callKind(),
                bareCallRoute.receiverKind(),
                null,
                bareCallRoute.receiverType(),
                argumentTypes,
                null,
                detailReason
        );
    }

    private @NotNull FrontendResolvedCall resolvedBareConstructorCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull List<GdType> argumentTypes,
            @NotNull FrontendConstructorResolutionSupport.Resolution resolution
    ) {
        return FrontendResolvedCall.resolved(
                bareCallee.name(),
                FrontendCallResolutionKind.CONSTRUCTOR,
                FrontendReceiverKind.TYPE_META,
                resolution.ownerKind(),
                receiverTypeMeta.instanceType(),
                receiverTypeMeta.instanceType(),
                argumentTypes,
                resolution.declarationSite()
        );
    }

    private @NotNull FrontendResolvedCall failedBareConstructorCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull List<GdType> argumentTypes,
            @NotNull FrontendConstructorResolutionSupport.Resolution resolution
    ) {
        return FrontendResolvedCall.failed(
                bareCallee.name(),
                FrontendCallResolutionKind.CONSTRUCTOR,
                FrontendReceiverKind.TYPE_META,
                resolution.ownerKind(),
                receiverTypeMeta.instanceType(),
                argumentTypes,
                resolution.declarationSite(),
                Objects.requireNonNull(resolution.detailReason(), "detailReason must not be null")
        );
    }

    private @Nullable ScopeOwnerKind ownerKind(@Nullable FunctionDef functionDef) {
        if (functionDef == null) {
            return null;
        }
        return switch (functionDef) {
            case LirFunctionDef _ -> ScopeOwnerKind.GDCC;
            case ExtensionBuiltinClass.ClassMethod _, ExtensionBuiltinClass.ConstructorInfo _ -> ScopeOwnerKind.BUILTIN;
            case ExtensionGdClass.ClassMethod _, ExtensionUtilityFunction _ -> ScopeOwnerKind.ENGINE;
            default -> null;
        };
    }

    private @Nullable GdType currentClassReceiverType(@NotNull IdentifierExpression anchor) {
        var currentScope = scopesByAst.get(Objects.requireNonNull(anchor, "anchor must not be null"));
        while (currentScope != null) {
            if (currentScope instanceof ClassScope classScope) {
                return new GdObjectType(classScope.getCurrentClass().getName());
            }
            currentScope = currentScope.getParentScope();
        }
        return null;
    }

    /// Fixed-argument call compatibility is another direct consumer of the typed-boundary matrix in
    /// `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`.
    /// Call resolution must reuse the shared frontend boundary helper instead of carrying a
    /// call-specific handwritten conversion table. The corresponding ordinary `(un)pack`
    /// materialization contract is documented in
    /// `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`.
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
            if (!FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(
                    classRegistry,
                    argumentTypes.get(index),
                    parameters.get(index).getType()
            )) {
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
            if (!FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(
                    classRegistry,
                    argumentType,
                    parameter.getType()
            )) {
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

    /// Typed containers keep richer source-level names such as `Array[int]`, but operator metadata
    /// is still owned by the raw builtin classes and uses raw operand names for matching.
    private @Nullable GdType resolveUnaryExactReturnType(
            @NotNull GodotOperator operator,
            @NotNull GdType operandType
    ) {
        var builtinClass = findOperatorOwnerClass(classRegistry, operandType);
        if (builtinClass == null) {
            return null;
        }
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            if (!StringUtil.trimToEmpty(classOperator.rightType()).isEmpty()) {
                continue;
            }
            var returnType = parseOperatorReturnType(classRegistry, classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    private static @NotNull GdType requireStableOperatorOperandType(
            @NotNull String operandName,
            @NotNull FrontendExpressionType operandType
    ) {
        var actualOperandType = Objects.requireNonNull(operandType, "operandType must not be null");
        if (actualOperandType.status() != FrontendExpressionTypeStatus.RESOLVED
                && actualOperandType.status() != FrontendExpressionTypeStatus.DYNAMIC) {
            throw new IllegalStateException(
                    Objects.requireNonNull(operandName, "operandName must not be null")
                            + " must be RESOLVED or DYNAMIC before binary operator typing"
            );
        }
        return Objects.requireNonNull(
                actualOperandType.publishedType(),
                operandName + ".publishedType() must not be null for stable operator typing"
        );
    }

    private static @Nullable GdType resolveBinarySpecialReturnType(
            @NotNull GodotOperator operator,
            @NotNull GdType publishedLeftType,
            @NotNull GdType publishedRightType
    ) {
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            return GdBoolType.BOOL;
        }
        if (operator == GodotOperator.ADD
                && publishedLeftType instanceof GdArrayType leftArrayType
                && publishedRightType instanceof GdArrayType rightArrayType
                && !(leftArrayType.getValueType() instanceof GdVariantType)
                && leftArrayType.getValueType().equals(rightArrayType.getValueType())) {
            return leftArrayType;
        }
        return null;
    }

    private static boolean isRuntimeOpenOperatorOperand(
            @NotNull FrontendExpressionType operandType,
            @NotNull GdType publishedOperandType
    ) {
        return operandType.status() == FrontendExpressionTypeStatus.DYNAMIC
                || publishedOperandType instanceof GdVariantType;
    }

    private static @Nullable GdType resolveBinaryExactReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GodotOperator operator,
            @NotNull GdType leftType,
            @NotNull GdType rightType
    ) {
        var builtinClass = findOperatorOwnerClass(classRegistry, leftType);
        if (builtinClass == null) {
            return null;
        }
        var normalizedRightType = StringUtil.trimToEmpty(operatorOperandTypeName(rightType));
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            var metadataRightType = StringUtil.trimToEmpty(classOperator.rightType());
            if (metadataRightType.isEmpty() || !metadataRightType.equals(normalizedRightType)) {
                continue;
            }
            var returnType = parseOperatorReturnType(classRegistry, classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    private static @Nullable ExtensionBuiltinClass findOperatorOwnerClass(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType operandType
    ) {
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                .findBuiltinClass(operatorOperandTypeName(operandType));
    }

    private static @NotNull String operatorOperandTypeName(@NotNull GdType operandType) {
        if (operandType instanceof GdArrayType) {
            return "Array";
        }
        if (operandType instanceof GdDictionaryType) {
            return "Dictionary";
        }
        return operandType.getTypeName();
    }

    private static @Nullable GdType parseOperatorReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull ExtensionBuiltinClass.ClassOperator classOperator
    ) {
        var returnTypeName = StringUtil.trimToEmpty(classOperator.returnType());
        if (returnTypeName.isEmpty()) {
            return null;
        }
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                .tryResolveDeclaredType(returnTypeName);
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
        return new ExpressionSemanticResult(expressionType, false, null);
    }

    private static @NotNull ExpressionSemanticResult rootOutcome(@NotNull FrontendExpressionType expressionType) {
        return rootOutcome(expressionType, null);
    }

    private static @NotNull ExpressionSemanticResult rootOutcome(
            @NotNull FrontendExpressionType expressionType,
            @Nullable FrontendResolvedCall publishedCallOrNull
    ) {
        return new ExpressionSemanticResult(expressionType, true, publishedCallOrNull);
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

    private record BareCallRoute(
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType receiverType
    ) {
        private BareCallRoute {
            Objects.requireNonNull(callKind, "callKind must not be null");
            Objects.requireNonNull(receiverKind, "receiverKind must not be null");
        }
    }
}
