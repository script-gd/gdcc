package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolution;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolver;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueStatus;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// Statement-local owner procedures used by the new body SuiteResolver path.
///
/// This class is intentionally root-bounded: it may walk the current statement/header expression, but
/// it must never start from a `SourceFile` or call any legacy whole-module analyzer entrypoint. Facts
/// are written only through `FrontendTypedLexicalEnvironment`, so pending/current-suite visibility and
/// ordered per-owner export stay centralized in one place.
public final class FrontendBodyOwnerProcedures implements FrontendStatementResolver.OwnerProcedures {
    private FrontendAnalysisData cachedAnalysisData;
    private FrontendVisibleValueResolver cachedVisibleValueResolver;

    @Override
    public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        forEachExpression(root, expression -> {
            if (expression instanceof IdentifierExpression identifierExpression) {
                bindIdentifier(context, identifierExpression);
            }
        });
    }

    @Override
    public void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        if (!(root instanceof VariableDeclaration variableDeclaration)) {
            return;
        }
        var blockScope = eligibleInferredLocalScope(context, variableDeclaration);
        if (blockScope == null || variableDeclaration.value() == null) {
            return;
        }
        var initializer = variableDeclaration.value();
        var guardedFailure = typeMetaOrdinaryValueInitializerFailure(context, initializer);
        if (guardedFailure == null) {
            guardedFailure = assignmentOrdinaryValueInitializerFailure(initializer);
        }
        var initializerType = guardedFailure != null
                ? guardedFailure
                : new BodyExpressionResolver(context).resolveExpressionType(initializer, false);
        var stableType = stableLocalTypeOrNull(initializerType);
        if (stableType == null) {
            return;
        }
        context.typedEnvironment().addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(
                        blockScope,
                        variableDeclaration.name().trim(),
                        variableDeclaration,
                        stableType
                )
        );
    }

    @Override
    public void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        var resolver = new BodyExpressionResolver(context);
        forEachExpression(root, expression -> {
            if (expression instanceof AttributeExpression attributeExpression) {
                var reduced = resolver.reduceAttributeExpression(attributeExpression);
                if (reduced != null) {
                    publishReduction(context, reduced);
                }
            }
        });
    }

    @Override
    public void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        var resolver = new BodyExpressionResolver(context);
        forEachExpression(root, expression -> publishExpressionType(context, resolver, expression));
    }

    @Override
    public void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        if (!(root instanceof VariableDeclaration variableDeclaration)
                || variableDeclaration.kind() != DeclarationKind.VAR) {
            return;
        }
        var declarationScope = context.analysisData().scopesByAst().get(variableDeclaration);
        if (!(declarationScope instanceof BlockScope blockScope)) {
            return;
        }
        var localName = variableDeclaration.name().trim();
        var slot = blockScope.resolveValueHere(localName);
        if (slot == null || slot.declaration() != variableDeclaration) {
            return;
        }
        var effectiveSlot = context.typedEnvironment().effectiveScopeValue(slot, blockScope);
        context.typedEnvironment().putSlotType(
                FrontendSemanticStage.VAR_TYPE_POST,
                variableDeclaration,
                effectiveSlot.type()
        );
    }

    private void bindIdentifier(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        if (context.typedEnvironment().symbolBinding(identifierExpression) != null) {
            return;
        }
        var valueResolution = resolveVisibleValue(context, identifierExpression);
        if (valueResolution.status() == FrontendVisibleValueStatus.FOUND_ALLOWED
                || valueResolution.status() == FrontendVisibleValueStatus.FOUND_BLOCKED) {
            publishScopeValueBinding(context, identifierExpression, valueResolution);
            return;
        }
        if (valueResolution.status() == FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED) {
            return;
        }
        if (tryPublishFunctionBinding(context, identifierExpression)) {
            return;
        }
        if (tryPublishTypeMetaBinding(context, identifierExpression)) {
            return;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(identifierExpression.name(), FrontendBindingKind.UNKNOWN, null)
        );
    }

    private @NotNull FrontendVisibleValueResolution resolveVisibleValue(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        if (context.propertyInitializerContext() != null) {
            var result = context.currentScope().resolveValue(identifierExpression.name(), context.restriction());
            return switch (result.status()) {
                case FOUND_ALLOWED -> FrontendVisibleValueResolution.foundAllowed(result.requireValue(), List.of());
                case FOUND_BLOCKED -> FrontendVisibleValueResolution.foundBlocked(result.requireValue(), List.of());
                case NOT_FOUND -> FrontendVisibleValueResolution.notFound(List.of());
            };
        }
        return visibleValueResolver(context).resolve(
                context.visibleValueResolveRequest(identifierExpression.name(), identifierExpression),
                context.typedEnvironment()
        );
    }

    private void publishScopeValueBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression,
            @NotNull FrontendVisibleValueResolution resolution
    ) {
        var resolvedValue = Objects.requireNonNull(resolution.visibleValue(), "visibleValue must not be null");
        var accessStatus = resolution.status() == FrontendVisibleValueStatus.FOUND_ALLOWED
                ? ScopeLookupStatus.FOUND_ALLOWED
                : ScopeLookupStatus.FOUND_BLOCKED;
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        toBindingKind(resolvedValue.kind()),
                        resolvedValue.declaration(),
                        resolvedValue,
                        accessStatus
                )
        );
    }

    private boolean tryPublishFunctionBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = currentScopeFor(context, identifierExpression);
        if (currentScope == null) {
            return false;
        }
        var functionResult = currentScope.resolveFunctions(identifierExpression.name(), context.restriction());
        if (!functionResult.isAllowed() && !functionResult.isBlocked()) {
            return false;
        }
        var bindingKind = classifyFunctionBinding(functionResult.requireValue());
        if (bindingKind == null) {
            return false;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        bindingKind,
                        List.copyOf(functionResult.requireValue())
                )
        );
        return true;
    }

    private boolean tryPublishTypeMetaBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = currentScopeFor(context, identifierExpression);
        if (currentScope == null) {
            return false;
        }
        var typeMetaResult = context.analysisData().moduleSkeleton().resolveSourceFacingTypeMeta(
                currentScope,
                identifierExpression.name(),
                context.restriction()
        );
        if (!typeMetaResult.isAllowed()) {
            return false;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        FrontendBindingKind.TYPE_META,
                        typeMetaResult.requireValue().declaration()
                )
        );
        return true;
    }

    private @Nullable BlockScope eligibleInferredLocalScope(
            @NotNull FrontendSuiteContext context,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        if (variableDeclaration.kind() != DeclarationKind.VAR
                || variableDeclaration.value() == null
                || !FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())) {
            return null;
        }
        var declarationScope = context.analysisData().scopesByAst().get(variableDeclaration);
        if (!(declarationScope instanceof BlockScope blockScope)
                || !FrontendExecutableInventorySupport.isCallableLocalValueInventoryReady(
                blockScope,
                context.currentBlockRoot(),
                context.interfaceSurface().inventoryGateRegistry()
        )) {
            return null;
        }
        var survivingLocal = blockScope.resolveValueHere(variableDeclaration.name().trim());
        if (survivingLocal == null || survivingLocal.declaration() != variableDeclaration) {
            return null;
        }
        return blockScope;
    }

    private @Nullable FrontendExpressionType typeMetaOrdinaryValueInitializerFailure(
            @NotNull FrontendSuiteContext context,
            @NotNull Expression initializer
    ) {
        if (!(initializer instanceof IdentifierExpression identifierExpression)) {
            return null;
        }
        var binding = context.typedEnvironment().symbolBinding(identifierExpression);
        if (binding == null || binding.kind() != FrontendBindingKind.TYPE_META) {
            return null;
        }
        return FrontendExpressionType.failed(
                "Type-meta initializer '" + identifierExpression.name()
                        + "' cannot stabilize an inferred local because it is not an ordinary value"
        );
    }

    private static @Nullable FrontendExpressionType assignmentOrdinaryValueInitializerFailure(
            @NotNull Expression initializer
    ) {
        if (!(initializer instanceof AssignmentExpression)) {
            return null;
        }
        return FrontendExpressionType.failed(
                "Assignment initializer cannot stabilize an inferred local because it is not an ordinary value"
        );
    }

    private static @Nullable GdType stableLocalTypeOrNull(@NotNull FrontendExpressionType initializerType) {
        if (initializerType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return null;
        }
        var publishedType = initializerType.publishedType();
        if (publishedType instanceof GdVoidType) {
            return null;
        }
        if (publishedType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend local stabilization: "
                            + compilerOnlyType.getTypeName()
            );
        }
        return publishedType;
    }

    private static void publishReduction(
            @NotNull FrontendSuiteContext context,
            @NotNull FrontendChainReductionHelper.ReductionResult result
    ) {
        for (var trace : result.stepTraces()) {
            if (trace.suggestedMember() != null) {
                context.typedEnvironment().putResolvedMember(
                        FrontendSemanticStage.CHAIN_BINDING,
                        trace.step(),
                        trace.suggestedMember()
                );
            }
            if (trace.suggestedCall() != null) {
                context.typedEnvironment().putResolvedCall(
                        FrontendSemanticStage.CHAIN_BINDING,
                        trace.step(),
                        trace.suggestedCall()
                );
            }
        }
    }

    private static void publishExpressionType(
            @NotNull FrontendSuiteContext context,
            @NotNull BodyExpressionResolver resolver,
            @NotNull Expression expression
    ) {
        var expressionType = resolver.resolveExpressionType(expression, true);
        context.typedEnvironment().putExpressionType(
                FrontendSemanticStage.EXPR_TYPE,
                expression,
                expressionType
        );
        if (expression instanceof CallExpression callExpression) {
            var resolvedCall = resolver.resolvedCall(callExpression);
            if (resolvedCall != null) {
                context.typedEnvironment().putResolvedCall(
                        FrontendSemanticStage.EXPR_TYPE,
                        callExpression,
                        resolvedCall
                );
            }
        }
    }

    private @NotNull FrontendVisibleValueResolver visibleValueResolver(@NotNull FrontendSuiteContext context) {
        if (cachedAnalysisData != context.analysisData() || cachedVisibleValueResolver == null) {
            cachedAnalysisData = context.analysisData();
            cachedVisibleValueResolver = new FrontendVisibleValueResolver(
                    context.analysisData(),
                    context.interfaceSurface().inventoryGateRegistry()
            );
        }
        return cachedVisibleValueResolver;
    }

    private static @Nullable Scope currentScopeFor(
            @NotNull FrontendSuiteContext context,
            @NotNull Node node
    ) {
        return context.analysisData().scopesByAst().get(node);
    }

    private static @Nullable FrontendBindingKind classifyFunctionBinding(@NotNull List<FunctionDef> overloadSet) {
        var overloads = List.copyOf(Objects.requireNonNull(overloadSet, "overloadSet must not be null"));
        if (overloads.isEmpty()) {
            return null;
        }
        if (overloads.stream().allMatch(ExtensionUtilityFunction.class::isInstance)) {
            return FrontendBindingKind.UTILITY_FUNCTION;
        }
        if (overloads.stream().anyMatch(ExtensionUtilityFunction.class::isInstance)) {
            return null;
        }
        if (overloads.stream().allMatch(FunctionDef::isStatic)) {
            return FrontendBindingKind.STATIC_METHOD;
        }
        if (overloads.stream().anyMatch(FunctionDef::isStatic)) {
            return null;
        }
        return FrontendBindingKind.METHOD;
    }

    private static @NotNull FrontendBindingKind toBindingKind(@NotNull ScopeValueKind scopeValueKind) {
        return switch (Objects.requireNonNull(scopeValueKind, "scopeValueKind must not be null")) {
            case LOCAL -> FrontendBindingKind.LOCAL_VAR;
            case PARAMETER -> FrontendBindingKind.PARAMETER;
            case CAPTURE -> FrontendBindingKind.CAPTURE;
            case PROPERTY -> FrontendBindingKind.PROPERTY;
            case SIGNAL -> FrontendBindingKind.SIGNAL;
            case CONSTANT -> FrontendBindingKind.CONSTANT;
            case SINGLETON -> FrontendBindingKind.SINGLETON;
            case GLOBAL_ENUM -> FrontendBindingKind.GLOBAL_ENUM;
            case TYPE_META -> FrontendBindingKind.TYPE_META;
        };
    }

    private static void forEachExpression(@NotNull Node root, @NotNull Consumer<Expression> consumer) {
        walkRootBounded(root, node -> {
            if (node instanceof Expression expression) {
                consumer.accept(expression);
            }
        });
    }

    private static void walkRootBounded(@NotNull Node node, @NotNull Consumer<Node> consumer) {
        consumer.accept(node);
        if (node instanceof LambdaExpression) {
            return;
        }
        for (var child : node.getChildren()) {
            walkRootBounded(child, consumer);
        }
    }

    private static final class BodyExpressionResolver {
        private final @NotNull FrontendSuiteContext context;
        // Procedure-local transient caches back bounded retry without joining the typed overlay.
        // Only explicit owner publication below can move final facts into pending/committed state.
        private final @NotNull IdentityHashMap<Expression, FrontendExpressionType> expressionTypes =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, FrontendExpressionType> finalizedExpressionTypes =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<CallExpression, FrontendResolvedCall> resolvedCalls =
                new IdentityHashMap<>();
        private final @NotNull FrontendChainReductionFacade chainReduction;
        private final @NotNull FrontendExpressionSemanticSupport expressionSemanticSupport;

        private BodyExpressionResolver(@NotNull FrontendSuiteContext context) {
            this.context = Objects.requireNonNull(context, "context must not be null");
            chainReduction = new FrontendChainReductionFacade(
                    context.analysisData(),
                    context.analysisData().scopesByAst(),
                    context::restriction,
                    context::staticContext,
                    context::propertyInitializerContext,
                    context.classRegistry(),
                    this::resolveExpressionDependency,
                    identifier -> context.typedEnvironment().symbolBinding(identifier)
            );
            expressionSemanticSupport = new FrontendExpressionSemanticSupport(
                    identifier -> context.typedEnvironment().symbolBinding(identifier),
                    context.analysisData().scopesByAst(),
                    context::restriction,
                    context::propertyInitializerContext,
                    context.classRegistry(),
                    chainReduction::headReceiverSupport
            );
        }

        private @NotNull FrontendExpressionType resolveExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            var published = context.typedEnvironment().expressionType(expression);
            if (published != null) {
                return published;
            }
            var cache = finalizeWindow ? finalizedExpressionTypes : expressionTypes;
            var cached = cache.get(expression);
            if (cached != null) {
                return cached;
            }
            var computed = computeExpressionType(expression, finalizeWindow);
            cache.put(expression, computed);
            if (finalizeWindow) {
                expressionTypes.put(expression, computed);
            }
            return computed;
        }

        private @NotNull FrontendExpressionType computeExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return switch (expression) {
                case LiteralExpression literalExpression -> expressionSemanticSupport
                        .resolveLiteralExpressionType(literalExpression)
                        .expressionType();
                case SelfExpression selfExpression -> expressionSemanticSupport
                        .resolveSelfExpressionType(selfExpression)
                        .expressionType();
                case IdentifierExpression identifierExpression -> expressionSemanticSupport
                        .resolveIdentifierExpressionType(identifierExpression)
                        .expressionType();
                case AttributeExpression attributeExpression -> resolveAttributeExpressionType(attributeExpression);
                case AssignmentExpression _ -> FrontendExpressionType.failed(
                        "Assignment expression typing is not part of the statement-local value contract yet"
                );
                case CallExpression callExpression -> resolveCallExpressionType(callExpression, finalizeWindow);
                case SubscriptExpression subscriptExpression -> expressionSemanticSupport
                        .resolveSubscriptExpressionType(
                                subscriptExpression,
                                this::resolveExpressionType,
                                finalizeWindow
                        )
                        .expressionType();
                case LambdaExpression lambdaExpression -> expressionSemanticSupport
                        .resolveLambdaExpressionType(
                                lambdaExpression,
                                this::resolveExpressionType,
                                false,
                                finalizeWindow
                        )
                        .expressionType();
                case UnaryExpression unaryExpression -> expressionSemanticSupport
                        .resolveUnaryExpressionType(
                                unaryExpression,
                                this::resolveExpressionType,
                                finalizeWindow
                        )
                        .expressionType();
                case BinaryExpression binaryExpression -> expressionSemanticSupport
                        .resolveBinaryExpressionType(
                                binaryExpression,
                                this::resolveExpressionType,
                                finalizeWindow
                        )
                        .expressionType();
                default -> expressionSemanticSupport
                        .resolveRemainingExplicitExpressionType(
                                expression,
                                this::resolveExpressionType,
                                true,
                                finalizeWindow
                        )
                        .expressionType();
            };
        }

        private @NotNull FrontendExpressionType resolveCallExpressionType(
                @NotNull CallExpression callExpression,
                boolean finalizeWindow
        ) {
            var result = expressionSemanticSupport.resolveCallExpressionType(
                    callExpression,
                    this::resolveExpressionType,
                    true,
                    finalizeWindow
            );
            if (result.publishedCallOrNull() != null) {
                resolvedCalls.put(callExpression, result.publishedCallOrNull());
            }
            return result.expressionType();
        }

        private @NotNull FrontendExpressionType resolveAttributeExpressionType(
                @NotNull AttributeExpression attributeExpression
        ) {
            var reduced = reduceAttributeExpression(attributeExpression);
            if (reduced == null) {
                return FrontendExpressionType.unsupported(
                        "Nested chain expression is inside an unsupported or skipped subtree"
                );
            }
            return FrontendChainStatusBridge.toPublishedExpressionType(reduced);
        }

        private @Nullable FrontendChainReductionHelper.ReductionResult reduceAttributeExpression(
                @NotNull AttributeExpression attributeExpression
        ) {
            return chainReduction.reduce(attributeExpression).result();
        }

        private @Nullable FrontendResolvedCall resolvedCall(@NotNull CallExpression callExpression) {
            return resolvedCalls.get(callExpression);
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveExpressionDependency(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return FrontendChainStatusBridge.toExpressionTypeResult(resolveExpressionType(expression, finalizeWindow));
        }
    }
}
