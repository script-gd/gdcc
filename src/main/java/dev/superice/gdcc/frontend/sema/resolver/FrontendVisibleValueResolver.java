package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeLookupResult;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Frontend-only visible-value resolver for executable-body use sites.
///
/// The resolver intentionally sits above the shared `Scope` protocol:
/// - shared scopes still answer lexical inventory and restriction-aware allowed/blocked/not-found
/// - this resolver adds declaration-order filtering, initializer self-reference suppression, and
///   explicit deferred-boundary sealing for domains whose variable inventory is not published yet
public final class FrontendVisibleValueResolver {
    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull IdentityHashMap<Node, Node> parentByNode = new IdentityHashMap<>();
    private final @NotNull IdentityHashMap<Node, Boolean> indexedAstNodes = new IdentityHashMap<>();

    public FrontendVisibleValueResolver(@NotNull FrontendAnalysisData analysisData) {
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        indexSourceAstParents(analysisData.moduleSkeleton());
    }

    /// Resolves one value use site under the frontend-visible declaration-order rules.
    public @NotNull FrontendVisibleValueResolution resolve(@NotNull FrontendVisibleValueResolveRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        if (request.domain() != FrontendVisibleValueDomain.EXECUTABLE_BODY) {
            return deferredUnsupported(
                    request.domain(),
                    FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN
            );
        }

        var useSite = request.useSite();
        var deferredBoundary = detectDeferredBoundary(useSite);
        if (deferredBoundary != null) {
            return FrontendVisibleValueResolution.deferredUnsupported(deferredBoundary);
        }

        var currentScope = analysisData.scopesByAst().get(useSite);
        if (currentScope == null) {
            return deferredUnsupported(
                    indexedAstNodes.containsKey(useSite)
                            ? FrontendVisibleValueDomain.EXECUTABLE_BODY
                            : FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE,
                    FrontendVisibleValueDeferredReason.MISSING_SCOPE_OR_SKIPPED_SUBTREE
            );
        }
        if (!isInsideSupportedExecutableScope(currentScope)) {
            return deferredUnsupported(
                    FrontendVisibleValueDomain.EXECUTABLE_BODY,
                    FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN
            );
        }

        var filteredHits = new ArrayList<FrontendFilteredValueHit>();
        var scope = currentScope;
        while (scope instanceof BlockScope || scope instanceof CallableScope) {
            var currentLayerResult = scope.resolveValueHere(request.name(), request.restriction());
            if (currentLayerResult.isBlocked()) {
                return FrontendVisibleValueResolution.foundBlocked(currentLayerResult.requireValue(), filteredHits);
            }
            if (currentLayerResult.isAllowed()) {
                var visibleValue = currentLayerResult.requireValue();
                var filteredHit = filterInvisibleCurrentLayerHit(visibleValue, scope, useSite);
                if (filteredHit == null) {
                    return FrontendVisibleValueResolution.foundAllowed(visibleValue, filteredHits);
                }
                filteredHits.add(filteredHit);
            }
            scope = scope.getParentScope();
        }

        if (scope == null) {
            return FrontendVisibleValueResolution.notFound(filteredHits);
        }
        return toFrontendResolution(scope.resolveValue(request.name(), request.restriction()), filteredHits);
    }

    private void indexSourceAstParents(@NotNull FrontendModuleSkeleton moduleSkeleton) {
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            indexSubtree(sourceClassRelation.unit().ast(), null);
        }
    }

    private void indexSubtree(@NotNull Node node, @Nullable Node parentNode) {
        indexedAstNodes.put(node, Boolean.TRUE);
        if (parentNode != null) {
            parentByNode.put(node, parentNode);
        }
        for (var child : node.getChildren()) {
            indexSubtree(child, node);
        }
    }

    /// Walks from the use site toward the source root and returns the nearest deferred boundary.
    ///
    /// Checking the nearest boundary first matters because nested unsupported regions should report
    /// the domain the binder actually crossed most recently instead of some broader outer umbrella.
    private @Nullable FrontendVisibleValueDeferredBoundary detectDeferredBoundary(@NotNull Node useSite) {
        Node currentNode = useSite;
        while (true) {
            var parentNode = parentByNode.get(currentNode);
            if (parentNode == null) {
                return null;
            }

            var boundary = classifyBoundaryEdge(currentNode, parentNode);
            if (boundary != null) {
                return boundary;
            }
            currentNode = parentNode;
        }
    }

    private @Nullable FrontendVisibleValueDeferredBoundary classifyBoundaryEdge(
            @NotNull Node childNode,
            @NotNull Node parentNode
    ) {
        return switch (parentNode) {
            case Parameter parameter when parameter.defaultValue() == childNode ->
                    new FrontendVisibleValueDeferredBoundary(
                            FrontendVisibleValueDomain.PARAMETER_DEFAULT,
                            FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
                    );
            case LambdaExpression lambdaExpression when lambdaExpression.body() == childNode ->
                    new FrontendVisibleValueDeferredBoundary(
                            FrontendVisibleValueDomain.LAMBDA_SUBTREE,
                            FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
                    );
            case ForStatement forStatement when (forStatement.iteratorType() == childNode
                    || forStatement.iterable() == childNode
                    || forStatement.body() == childNode) -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.FOR_SUBTREE,
                    FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
            );
            case MatchSection matchSection when (matchSection.guard() == childNode
                    || matchSection.body() == childNode
                    || containsNodeIdentity(matchSection.patterns(), childNode)) -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.MATCH_SUBTREE,
                    FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
            );
            default -> null;
        };
    }

    private boolean isInsideSupportedExecutableScope(@NotNull Scope scope) {
        Scope currentScope = scope;
        while (currentScope != null) {
            if (currentScope instanceof BlockScope blockScope && isSupportedExecutableBlock(blockScope.kind())) {
                return true;
            }
            currentScope = currentScope.getParentScope();
        }
        return false;
    }

    /// Deferred-boundary checks must follow AST identity, matching the rest of the resolver's
    /// parent/index tables, instead of relying on structural record equality.
    private boolean containsNodeIdentity(@NotNull List<? extends Node> nodes, @NotNull Node targetNode) {
        for (var node : nodes) {
            if (node == targetNode) {
                return true;
            }
        }
        return false;
    }

    private boolean isSupportedExecutableBlock(@NotNull BlockScopeKind kind) {
        return switch (kind) {
            case FUNCTION_BODY,
                 CONSTRUCTOR_BODY,
                 BLOCK_STATEMENT,
                 IF_BODY,
                 ELIF_BODY,
                 ELSE_BODY,
                 WHILE_BODY -> true;
            default -> false;
        };
    }

    private @Nullable FrontendFilteredValueHit filterInvisibleCurrentLayerHit(
            @NotNull ScopeValue value,
            @NotNull Scope owningScope,
            @NotNull Node useSite
    ) {
        var declaration = value.declaration();
        if (declaration instanceof Parameter) {
            return null;
        }
        if (declaration instanceof VariableDeclaration variableDeclaration) {
            if (isVisibleLocal(variableDeclaration, useSite)) {
                return null;
            }
            return new FrontendFilteredValueHit(
                    value,
                    owningScope,
                    determineLocalFilterReason(variableDeclaration, useSite)
            );
        }
        return null;
    }

    private boolean isVisibleLocal(@NotNull VariableDeclaration variableDeclaration, @NotNull Node useSite) {
        return variableDeclaration.range().endByte() <= useSite.range().startByte();
    }

    private @NotNull FrontendFilteredValueHitReason determineLocalFilterReason(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull Node useSite
    ) {
        return isDescendantOf(useSite, variableDeclaration.value())
                ? FrontendFilteredValueHitReason.SELF_REFERENCE_IN_INITIALIZER
                : FrontendFilteredValueHitReason.DECLARATION_AFTER_USE_SITE;
    }

    private boolean isDescendantOf(@NotNull Node candidateDescendant, @Nullable Node ancestor) {
        if (ancestor == null) {
            return false;
        }
        Node currentNode = candidateDescendant;
        while (true) {
            if (currentNode == ancestor) {
                return true;
            }
            currentNode = parentByNode.get(currentNode);
            if (currentNode == null) {
                return false;
            }
        }
    }

    private @NotNull FrontendVisibleValueResolution toFrontendResolution(
            @NotNull ScopeLookupResult<ScopeValue> scopeResult,
            @NotNull List<FrontendFilteredValueHit> filteredHits
    ) {
        return switch (scopeResult.status()) {
            case FOUND_ALLOWED -> FrontendVisibleValueResolution.foundAllowed(
                    scopeResult.requireValue(),
                    filteredHits
            );
            case FOUND_BLOCKED -> FrontendVisibleValueResolution.foundBlocked(
                    scopeResult.requireValue(),
                    filteredHits
            );
            case NOT_FOUND -> FrontendVisibleValueResolution.notFound(filteredHits);
        };
    }

    private @NotNull FrontendVisibleValueResolution deferredUnsupported(
            @NotNull FrontendVisibleValueDomain domain,
            @NotNull FrontendVisibleValueDeferredReason reason
    ) {
        return FrontendVisibleValueResolution.deferredUnsupported(
                new FrontendVisibleValueDeferredBoundary(domain, reason)
        );
    }
}
