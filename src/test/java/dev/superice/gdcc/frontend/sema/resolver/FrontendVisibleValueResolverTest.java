package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.exception.ScopeLookupException;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendVisibleValueResolverTest {
    @Test
    void resolveFindsVisibleParameterInsideExecutableBody() throws Exception {
        var analyzedInput = analyzedInput("visible_parameter.gd", """
                class_name VisibleParameter
                extends Node

                func ping(value: int):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PARAMETER, result.visibleValue().kind());
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        assertSame(pingFunction.parameters().getFirst(), result.visibleValue().declaration());
        assertTrue(result.filteredHits().isEmpty());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveReturnsNotFoundWithFilteredFutureLocal() throws Exception {
        var analyzedInput = analyzedInput("future_local_not_found.gd", """
                class_name FutureLocalNotFound
                extends Node

                func ping():
                    print(count)
                    var count := 1
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "count");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "count",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.NOT_FOUND, result.status());
        assertNull(result.visibleValue());
        assertNull(result.deferredBoundary());
        assertEquals(1, result.filteredHits().size());
        assertEquals(
                FrontendFilteredValueHitReason.DECLARATION_AFTER_USE_SITE,
                result.primaryFilteredHit().reason()
        );
        assertEquals(ScopeValueKind.LOCAL, result.primaryFilteredHit().value().kind());
    }

    @Test
    void resolveFiltersInitializerSelfReferenceAndFallsBackToOuterClassProperty() throws Exception {
        var analyzedInput = analyzedInput("self_reference_initializer.gd", """
                class_name SelfReferenceInitializer
                extends Node

                var node = 7

                func ping():
                    var node = node
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "node");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "node",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PROPERTY, result.visibleValue().kind());
        assertEquals(1, result.filteredHits().size());
        assertEquals(
                FrontendFilteredValueHitReason.SELF_REFERENCE_IN_INITIALIZER,
                result.primaryFilteredHit().reason()
        );
        assertEquals(ScopeValueKind.LOCAL, result.primaryFilteredHit().value().kind());
    }

    @Test
    void resolveKeepsFutureLocalProvenanceBeforeVisibleClassProperty() throws Exception {
        var analyzedInput = analyzedInput("class_property_with_future_local.gd", """
                class_name ClassPropertyWithFutureLocal
                extends Node

                var a = 1

                func ping():
                    print(a)
                    var a = 2
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "a");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "a",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PROPERTY, result.visibleValue().kind());
        assertEquals(1, result.filteredHits().size());
        assertEquals(ScopeValueKind.LOCAL, result.primaryFilteredHit().value().kind());
        assertEquals(
                FrontendFilteredValueHitReason.DECLARATION_AFTER_USE_SITE,
                result.primaryFilteredHit().reason()
        );
    }

    @Test
    void resolvePreservesBlockedClassMemberHitInsteadOfDowngradingToNotFound() throws Exception {
        var analyzedInput = analyzedInput("blocked_class_property.gd", """
                class_name BlockedClassProperty
                extends Node

                var hp: int = 1

                func ping():
                    print(hp)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "hp");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "hp",
                useSite,
                ResolveRestriction.staticContext(),
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_BLOCKED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PROPERTY, result.visibleValue().kind());
        assertTrue(result.filteredHits().isEmpty());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveSealsParameterDefaultSubtreeAsDeferredUnsupported() throws Exception {
        var analyzedInput = analyzedInput("parameter_default_deferred.gd", """
                class_name ParameterDefaultDeferred
                extends Node

                func ping(value, alias = value):
                    return alias
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.parameters().getLast().defaultValue(), "value");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertNull(result.visibleValue());
        assertTrue(result.filteredHits().isEmpty());
        assertEquals(FrontendVisibleValueDomain.PARAMETER_DEFAULT, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveSealsLambdaBodyAsDeferredUnsupported() throws Exception {
        var analyzedInput = analyzedInput("lambda_body_deferred.gd", """
                class_name LambdaBodyDeferred
                extends Node

                func ping(seed: int):
                    var f = func():
                        return seed
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "seed");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "seed",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.LAMBDA_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveSealsBlockLocalConstInitializerAsDeferredUnsupported() throws Exception {
        var analyzedInput = analyzedInput("block_local_const_deferred.gd", """
                class_name BlockLocalConstDeferred
                extends Node

                func ping(seed: int):
                    const answer = seed
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "seed");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "seed",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertNull(result.visibleValue());
        assertTrue(result.filteredHits().isEmpty());
        assertEquals(FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveDoesNotFallBackPastVisibleBlockLocalConst() throws Exception {
        var analyzedInput = analyzedInput("visible_block_local_const_deferred.gd", """
                class_name VisibleBlockLocalConstDeferred
                extends Node

                var answer = 99

                func ping():
                    const answer = 1
                    print(answer)
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "answer");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "answer",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertNull(result.visibleValue());
        assertTrue(result.filteredHits().isEmpty());
        assertEquals(FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveKeepsOrdinaryLocalInitializerInsideExecutableBodySupported() throws Exception {
        var analyzedInput = analyzedInput("ordinary_local_initializer_supported.gd", """
                class_name OrdinaryLocalInitializerSupported
                extends Node

                func ping(seed: int):
                    var answer = seed
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "seed");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "seed",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PARAMETER, result.visibleValue().kind());
        assertTrue(result.filteredHits().isEmpty());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveSealsForBodyUseSiteAsDeferredUnsupported() throws Exception {
        var analyzedInput = analyzedInput("for_body_deferred.gd", """
                class_name ForBodyDeferred
                extends Node

                var item = 100

                func ping(values):
                    for item in values:
                        print(item)
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "item");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "item",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertNull(result.visibleValue());
        assertTrue(result.filteredHits().isEmpty());
        assertEquals(FrontendVisibleValueDomain.FOR_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveSealsForIterableAsDeferredUnsupported() throws Exception {
        var analyzedInput = analyzedInput("for_iterable_deferred.gd", """
                class_name ForIterableDeferred
                extends Node

                var item = 100

                func ping():
                    for item in [item]:
                        pass
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "item");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "item",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.FOR_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveRejectsSyntheticForBodyCurrentScopeEvenWithoutForAstBoundary() throws Exception {
        var analyzedInput = analyzedInput("synthetic_for_body_scope.gd", """
                class_name SyntheticForBodyScope
                extends Node

                func ping(value):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        var originalScope = assertInstanceOf(BlockScope.class, analyzedInput.analysisData().scopesByAst().get(useSite));
        analyzedInput.analysisData().scopesByAst().put(useSite, new BlockScope(originalScope, BlockScopeKind.FOR_BODY));
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.FOR_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveRejectsSyntheticMatchSectionCurrentScopeEvenWithoutMatchAstBoundary() throws Exception {
        var analyzedInput = analyzedInput("synthetic_match_scope.gd", """
                class_name SyntheticMatchScope
                extends Node

                func ping(value):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        var originalScope = assertInstanceOf(BlockScope.class, analyzedInput.analysisData().scopesByAst().get(useSite));
        analyzedInput.analysisData().scopesByAst().put(
                useSite,
                new BlockScope(originalScope, BlockScopeKind.MATCH_SECTION_BODY)
        );
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.MATCH_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveRejectsSyntheticLambdaBodyCurrentScopeEvenWithoutLambdaAstBoundary() throws Exception {
        var analyzedInput = analyzedInput("synthetic_lambda_scope.gd", """
                class_name SyntheticLambdaScope
                extends Node

                func ping(value):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        var originalScope = assertInstanceOf(BlockScope.class, analyzedInput.analysisData().scopesByAst().get(useSite));
        analyzedInput.analysisData().scopesByAst().put(useSite, new BlockScope(originalScope, BlockScopeKind.LAMBDA_BODY));
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.LAMBDA_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveSealsMatchSectionBodyAsDeferredUnsupported() throws Exception {
        var analyzedInput = analyzedInput("match_section_deferred.gd", """
                class_name MatchSectionDeferred
                extends Node

                func ping(value):
                    match value:
                        var bound when bound > 0:
                            print(bound)
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "bound");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "bound",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.MATCH_SUBTREE, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.VARIABLE_INVENTORY_NOT_PUBLISHED,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveRejectsUnsupportedRequestDomainBeforeLookup() throws Exception {
        var analyzedInput = analyzedInput("unsupported_request_domain.gd", """
                class_name UnsupportedRequestDomain
                extends Node

                func ping(value):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.PARAMETER_DEFAULT
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.PARAMETER_DEFAULT, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveReturnsDeferredUnsupportedWhenUseSiteScopeIsMissing() throws Exception {
        var analyzedInput = analyzedInput("missing_use_site_scope.gd", """
                class_name MissingUseSiteScope
                extends Node

                func ping(value):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        analyzedInput.analysisData().scopesByAst().remove(useSite);
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, result.status());
        assertEquals(FrontendVisibleValueDomain.EXECUTABLE_BODY, result.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.MISSING_SCOPE_OR_SKIPPED_SUBTREE,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolvePropagatesSharedScopeExceptionFromClassLookup() throws Exception {
        var analyzedInput = analyzedInput("shared_scope_exception.gd", """
                class_name SharedScopeException
                extends Node

                func ping():
                    print(missing_member)
                """);
        analyzedInput.analysisData().moduleSkeleton().classDefs().getFirst().setSuperName("MissingParent");
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "missing_member");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        assertThrows(ScopeLookupException.class, () -> resolver.resolve(new FrontendVisibleValueResolveRequest(
                "missing_member",
                useSite,
                ResolveRestriction.instanceContext(),
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        )));
    }

    private static @NotNull AnalyzedInput analyzedInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var analysisData = new FrontendSemanticAnalyzer().analyze(
                "test_module",
                List.of(unit),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedInput(unit, analysisData, diagnostics);
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull SourceFile sourceFile,
            @NotNull String name
    ) {
        return findNode(
                sourceFile,
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals(name)
        );
    }

    private static @NotNull IdentifierExpression findIdentifierExpression(
            @NotNull Node root,
            @NotNull String name
    ) {
        return findNode(
                root,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals(name)
        );
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        Objects.requireNonNull(node, "node must not be null");
        if (nodeType.isInstance(node)) {
            var candidate = nodeType.cast(node);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
