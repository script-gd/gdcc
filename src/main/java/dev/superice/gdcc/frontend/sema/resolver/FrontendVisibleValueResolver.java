package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton;
import dev.superice.gdcc.frontend.scope.CallableScopeKind;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeLookupResult;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.Statement;
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
        var currentScopeBoundary = classifyUnsupportedCurrentScopeBoundary(currentScope);
        if (currentScopeBoundary != null) {
            return FrontendVisibleValueResolution.deferredUnsupported(currentScopeBoundary);
        }
        var deferredLocalConstBoundary = detectDeferredVisibleBlockLocalConst(request.name(), useSite);
        if (deferredLocalConstBoundary != null) {
            return FrontendVisibleValueResolution.deferredUnsupported(deferredLocalConstBoundary);
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
            case VariableDeclaration variableDeclaration when variableDeclaration.kind() == DeclarationKind.CONST
                    && variableDeclaration.value() == childNode
                    && isDeferredBlockLocalConst(variableDeclaration) -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE,
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

    /// Class-level `const` continues to belong to class-scope lookup. Only executable block-local
    /// `const` declarations are sealed as deferred unsupported boundaries here.
    private boolean isDeferredBlockLocalConst(@NotNull VariableDeclaration variableDeclaration) {
        var declarationScope = analysisData.scopesByAst().get(variableDeclaration);
        return declarationScope instanceof BlockScope blockScope
                && FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind());
    }

    /// Block-local `const` inventory is still deferred. If a same-name visible `const` declaration
    /// already appears before the current use site, the resolver must refuse to masquerade as a
    /// normal miss or fall back to an outer binding.
    private @Nullable FrontendVisibleValueDeferredBoundary detectDeferredVisibleBlockLocalConst(
            @NotNull String name,
            @NotNull Node useSite
    ) {
        Node currentNode = useSite;
        while (true) {
            var parentNode = parentByNode.get(currentNode);
            if (parentNode == null) {
                return null;
            }
            if (parentNode instanceof Block block && publishesCallableLocalValueInventory(block)) {
                var boundary = detectDeferredVisibleBlockLocalConstInBlock(name, useSite, block, currentNode);
                if (boundary != null) {
                    return boundary;
                }
            }
            currentNode = parentNode;
        }
    }

    private @Nullable FrontendVisibleValueDeferredBoundary detectDeferredVisibleBlockLocalConstInBlock(
            @NotNull String name,
            @NotNull Node useSite,
            @NotNull Block block,
            @NotNull Node currentChild
    ) {
        for (var statement : block.statements()) {
            if (statement == currentChild) {
                return null;
            }
            var boundary = classifyVisibleDeferredBlockLocalConst(name, useSite, statement);
            if (boundary != null) {
                return boundary;
            }
        }
        return null;
    }

    private @Nullable FrontendVisibleValueDeferredBoundary classifyVisibleDeferredBlockLocalConst(
            @NotNull String name,
            @NotNull Node useSite,
            @NotNull Statement statement
    ) {
        if (!(statement instanceof VariableDeclaration variableDeclaration)
                || variableDeclaration.kind() != DeclarationKind.CONST
                || !variableDeclaration.name().trim().equals(name)
                || !isVisibleLocal(variableDeclaration, useSite)) {
            return null;
        }
        return new FrontendVisibleValueDeferredBoundary(
                FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE,
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
        );
    }

    /// AST edge detection seals unsupported fragments such as parameter defaults or `for` iterables
    /// that still reuse one outer supported scope. This current-scope gate exists as the fail-closed
    /// backstop for unsupported bodies whose own scope is already published in `scopesByAst()`.
    private @Nullable FrontendVisibleValueDeferredBoundary classifyUnsupportedCurrentScopeBoundary(
            @NotNull Scope currentScope
    ) {
        return switch (currentScope) {
            case BlockScope blockScope -> classifyUnsupportedCurrentBlockScopeBoundary(blockScope.kind());
            case CallableScope callableScope when callableScope.kind() == CallableScopeKind.LAMBDA_EXPRESSION ->
                    new FrontendVisibleValueDeferredBoundary(
                            FrontendVisibleValueDomain.LAMBDA_SUBTREE,
                            FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
                    );
            default -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.EXECUTABLE_BODY,
                    FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN
            );
        };
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

    private @Nullable FrontendVisibleValueDeferredBoundary classifyUnsupportedCurrentBlockScopeBoundary(
            @NotNull BlockScopeKind kind
    ) {
        if (FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(kind)) {
            return null;
        }
        return switch (kind) {
            case LAMBDA_BODY -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.LAMBDA_SUBTREE,
                    FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
            );
            case FOR_BODY -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.FOR_SUBTREE,
                    FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
            );
            case MATCH_SECTION_BODY -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.MATCH_SUBTREE,
                    FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED
            );
            default -> new FrontendVisibleValueDeferredBoundary(
                    FrontendVisibleValueDomain.EXECUTABLE_BODY,
                    FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN
            );
        };
    }

    private boolean publishesCallableLocalValueInventory(@NotNull Block block) {
        var blockScope = analysisData.scopesByAst().get(block);
        return blockScope instanceof BlockScope typedBlockScope
                && FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(typedBlockScope.kind());
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
