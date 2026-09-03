package gd.script.gdcc.frontend.sema.resolver;

import gd.script.gdcc.exception.ScopeLookupException;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendBodyDeclarationIndex;
import gd.script.gdcc.frontend.sema.FrontendBodyLocalDeclaration;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void resolvePrefersCallableLocalShadowingBareGlobalEnumValue() throws Exception {
        var analyzedInput = analyzedInput("shadow_global_enum_value.gd", """
                class_name ShadowGlobalEnumValue
                extends Node
                
                func ping():
                    var TYPE_NIL = 5
                    return TYPE_NIL
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "TYPE_NIL");
        var declaration = findNode(
                analyzedInput.unit().ast(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("TYPE_NIL")
        );
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "TYPE_NIL",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.LOCAL, result.visibleValue().kind());
        assertSame(declaration, result.visibleValue().declaration());
        assertTrue(result.filteredHits().isEmpty());

        // Shadowing a global constant with a callable-local variable is legal: the variable
        // analyzer's same-callable conflict check never reaches the global namespace, so no
        // diagnostic is published and top binding resolves the use site to the local.
        var errors = analyzedInput.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
        assertTrue(errors.isEmpty(), errors::toString);
        var binding = analyzedInput.analysisData().symbolBindings().get(useSite);
        assertNotNull(binding);
        assertEquals(FrontendBindingKind.LOCAL_VAR, binding.kind());
        assertSame(declaration, binding.declarationSite());
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
    void resolveValidatesPublishedDeclarationIdentityWithoutReplacingRangeFilter() throws Exception {
        var analyzedInput = analyzedInput("future_local_index.gd", """
                class_name FutureLocalIndex
                extends Node
                
                func ping():
                    print(count)
                    var count := 1
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "count");
        var declaration = findNode(
                pingFunction.body(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("count")
        );
        var scope = assertInstanceOf(BlockScope.class, analyzedInput.analysisData().scopesByAst().get(declaration));
        var binding = Objects.requireNonNull(scope.resolveValueHere("count"), "count local must be published");
        var publishedIndex = new FrontendBodyDeclarationIndex(Map.of(
                pingFunction.body(),
                List.of(new FrontendBodyLocalDeclaration(
                        declaration,
                        binding,
                        FrontendBodyLocalDeclaration.Kind.ORDINARY_VAR,
                        0
                ))
        ));
        var resolver = new FrontendVisibleValueResolver(
                analyzedInput.analysisData(),
                publishedIndex
        );

        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "count",
                useSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.NOT_FOUND, result.status());
        assertEquals(
                FrontendFilteredValueHitReason.DECLARATION_AFTER_USE_SITE,
                result.primaryFilteredHit().reason()
        );

        var incompleteIndex = new FrontendBodyDeclarationIndex(Map.of());
        var incompleteResolver = new FrontendVisibleValueResolver(
                analyzedInput.analysisData(),
                incompleteIndex
        );
        assertThrows(IllegalStateException.class, () -> incompleteResolver.resolve(
                new FrontendVisibleValueResolveRequest(
                        "count",
                        useSite,
                        FrontendVisibleValueDomain.EXECUTABLE_BODY
                )
        ));
    }

    @Test
    void resolveWithTypedEnvironmentUsesOverlayTypeWithoutBypassingVisibilityFilter() throws Exception {
        var visibleInput = analyzedInput("visible_overlay_local.gd", """
                class_name VisibleOverlayLocal
                extends Node
                
                func ping():
                    var count
                    print(count)
                """);
        var visibleFunction = findFunction(visibleInput.unit().ast(), "ping");
        var visibleUseSite = findIdentifierExpression(visibleFunction.body(), "count");
        var visibleResolver = new FrontendVisibleValueResolver(visibleInput.analysisData());
        var visibleBaseline = visibleResolver.resolve(new FrontendVisibleValueResolveRequest(
                "count",
                visibleUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));
        var visibleScope = assertInstanceOf(BlockScope.class, visibleInput.analysisData().scopesByAst().get(visibleUseSite));
        var visibleEnvironment = new FrontendTypedLexicalEnvironment(visibleScope, visibleInput.analysisData());
        visibleEnvironment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(
                        visibleScope,
                        "count",
                        visibleBaseline.visibleValue().declaration(),
                        GdIntType.INT
                )
        );

        var visibleWithOverlay = visibleResolver.resolve(new FrontendVisibleValueResolveRequest(
                "count",
                visibleUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ), visibleEnvironment);

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, visibleWithOverlay.status());
        assertSame(GdVariantType.VARIANT, visibleBaseline.visibleValue().type());
        assertSame(GdIntType.INT, visibleWithOverlay.visibleValue().type());
        assertSame(GdVariantType.VARIANT, visibleScope.resolveValueHere("count").type());

        var futureInput = analyzedInput("future_overlay_local.gd", """
                class_name FutureOverlayLocal
                extends Node
                
                func ping():
                    print(later)
                    var later
                """);
        var futureUseSite = findIdentifierExpression(futureInput.unit().ast(), "later");
        var futureResolver = new FrontendVisibleValueResolver(futureInput.analysisData());
        var futureBaseline = futureResolver.resolve(new FrontendVisibleValueResolveRequest(
                "later",
                futureUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));
        var filteredHit = futureBaseline.primaryFilteredHit();
        var futureEnvironment = new FrontendTypedLexicalEnvironment(filteredHit.owningScope(), futureInput.analysisData());
        var futureScope = assertInstanceOf(BlockScope.class, filteredHit.owningScope());
        futureEnvironment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(
                        futureScope,
                        "later",
                        filteredHit.value().declaration(),
                        GdIntType.INT
                )
        );

        var futureWithOverlay = futureResolver.resolve(new FrontendVisibleValueResolveRequest(
                "later",
                futureUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ), futureEnvironment);

        assertEquals(FrontendVisibleValueStatus.NOT_FOUND, futureWithOverlay.status());
        assertNull(futureWithOverlay.visibleValue());
        assertEquals(
                FrontendFilteredValueHitReason.DECLARATION_AFTER_USE_SITE,
                futureWithOverlay.primaryFilteredHit().reason()
        );
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
                FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN,
                result.deferredBoundary().reason()
        );
    }

    @Test
    void resolveFindsOuterCaptureAcrossLambdaEdge() throws Exception {
        var analyzedInput = analyzedInput("lambda_body_resolved.gd", """
                class_name LambdaBodyResolved
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

        // The lambda edge no longer seals: the use site sees the lambda's own CAPTURE binding,
        // filled with the declaration-site type of the outer parameter.
        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.CAPTURE, result.visibleValue().kind());
        assertEquals(GdIntType.INT, result.visibleValue().type());
        assertTrue(result.filteredHits().isEmpty());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveFindsLambdaParameterAndLocalAcrossLambdaEdge() throws Exception {
        var analyzedInput = analyzedInput("lambda_body_own_bindings.gd", """
                class_name LambdaBodyOwnBindings
                extends Node
                
                func ping():
                    var f = func(step):
                        var local := step
                        return local
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var lambda = findLambdaExpression(pingFunction.body());
        var stepUseSite = findIdentifierExpression(lambda.body(), "step");
        var localUseSite = findIdentifierExpression(lambda.body(), "local");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var parameterResult = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "step",
                stepUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));
        var localResult = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "local",
                localUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, parameterResult.status());
        assertNotNull(parameterResult.visibleValue());
        assertEquals(ScopeValueKind.PARAMETER, parameterResult.visibleValue().kind());
        assertNull(parameterResult.deferredBoundary());
        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, localResult.status());
        assertNotNull(localResult.visibleValue());
        assertEquals(ScopeValueKind.LOCAL, localResult.visibleValue().kind());
        assertNull(localResult.deferredBoundary());
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
                FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN,
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
                FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN,
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
    void resolveForBodyIteratorAsVisibleLocalWithoutFallingBackToClassProperty() throws Exception {
        var analyzedInput = analyzedInput("for_body_iterator_visible.gd", """
                class_name ForBodyIteratorVisible
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

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.LOCAL, result.visibleValue().kind());
        assertInstanceOf(ForStatement.class, result.visibleValue().declaration());
        assertTrue(result.filteredHits().isEmpty());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveForBodyCanReadOuterParameterThroughNormalLookup() throws Exception {
        var analyzedInput = analyzedInput("for_body_outer_parameter.gd", """
                class_name ForBodyOuterParameter
                extends Node
                
                func ping(values, seed):
                    for item in values:
                        print(seed)
                """);
        var forStatement = findNode(analyzedInput.unit().ast(), ForStatement.class, _ -> true);
        var useSite = findIdentifierExpression(forStatement.body(), "seed");
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
    void resolveForIterableInOuterScopeNormally() throws Exception {
        var analyzedInput = analyzedInput("for_iterable_outer_scope.gd", """
                class_name ForIterableOuterScope
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

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PROPERTY, result.visibleValue().kind());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveAllowsSyntheticForBodyCurrentScopeWithoutForAstBoundary() throws Exception {
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

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PARAMETER, result.visibleValue().kind());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveAllowsSyntheticMatchSectionCurrentScopeWithoutMatchAstBoundary() throws Exception {
        // MATCH_SECTION_BODY publishes lexical inventory, so the current-scope backstop no longer
        // seals a synthetic MATCH_SECTION_BODY scope. Without a real match AST boundary the request
        // resolves as an ordinary EXECUTABLE_BODY lookup (aligned with the FOR_BODY / LAMBDA_BODY
        // dual tests).
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

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PARAMETER, result.visibleValue().kind());
        assertNull(result.deferredBoundary());
    }

    @Test
    void resolveAllowsSyntheticLambdaBodyCurrentScopeWithoutLambdaAstBoundary() throws Exception {
        // LAMBDA_BODY publishes lexical inventory, so the
        // current-scope backstop no longer seals a synthetic LAMBDA_BODY scope. Without a real
        // lambda AST boundary the request resolves as an ordinary EXECUTABLE_BODY lookup.
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

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.PARAMETER, result.visibleValue().kind());
    }

    @Test
    void resolveAllowsMatchSectionBindInGuardAndBodyAndSealsOutsideSection() throws Exception {
        var analyzedInput = analyzedInput("match_section_visible.gd", """
                class_name MatchSectionVisible
                extends Node
                
                func ping(value):
                    match value:
                        var bound when bound > 0:
                            print(bound)
                    print(bound)
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var matchStatement = findNode(pingFunction.body(), MatchStatement.class, _ -> true);
        var guardUse = findIdentifierExpression(matchStatement.sections().getFirst().guard(), "bound");
        var bodyUse = findIdentifierExpression(matchStatement.sections().getFirst().body(), "bound");
        var afterUse = findIdentifierExpression(
                pingFunction.body().statements().getLast(),
                "bound"
        );
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        var guardResult = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "bound",
                guardUse,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));
        var bodyResult = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "bound",
                bodyUse,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));
        var afterResult = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "bound",
                afterUse,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, guardResult.status());
        assertEquals(ScopeValueKind.LOCAL, guardResult.visibleValue().kind());
        assertEquals(FrontendVisibleValueStatus.FOUND_ALLOWED, bodyResult.status());
        assertEquals(ScopeValueKind.LOCAL, bodyResult.visibleValue().kind());
        assertEquals(FrontendVisibleValueStatus.NOT_FOUND, afterResult.status());
        assertNull(afterResult.visibleValue());
    }

    @Test
    void resolveParameterDefaultDomainStopsCallableLocalHitsAtCurrentLayer() throws Exception {
        var analyzedInput = analyzedInput("unsupported_request_domain.gd", """
                class_name UnsupportedRequestDomain
                extends Node
                
                func ping(value):
                    print(value)
                """);
        var useSite = findIdentifierExpression(analyzedInput.unit().ast(), "value");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        // The parameter-default island domain resolves through the ordinary layer walk, but a
        // callable-local hit (parameter/capture/local) stops blocked at the current layer instead
        // of falling through to an outer same-name binding.
        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.PARAMETER_DEFAULT
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_BLOCKED, result.status());
        assertEquals(ScopeValueKind.PARAMETER, result.visibleValue().kind());

        // Other non-executable domains still reject before any lookup happens.
        var lambdaDomainResult = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "value",
                useSite,
                FrontendVisibleValueDomain.LAMBDA_SUBTREE
        ));
        assertEquals(FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED, lambdaDomainResult.status());
        assertEquals(FrontendVisibleValueDomain.LAMBDA_SUBTREE, lambdaDomainResult.deferredBoundary().domain());
        assertEquals(
                FrontendVisibleValueDeferredReason.UNSUPPORTED_DOMAIN,
                lambdaDomainResult.deferredBoundary().reason()
        );
    }

    @Test
    void resolveParameterDefaultDomainStopsCaptureHitsAtCurrentLayer() throws Exception {
        var analyzedInput = analyzedInput("parameter_default_capture_stop.gd", """
                class_name ParameterDefaultCaptureStop
                extends Node
                
                func ping(seed: int):
                    var f = func():
                        return seed
                """);
        var pingFunction = findFunction(analyzedInput.unit().ast(), "ping");
        var lambda = findLambdaExpression(pingFunction.body());
        var useSite = findIdentifierExpression(lambda.body(), "seed");
        var resolver = new FrontendVisibleValueResolver(analyzedInput.analysisData());

        // Inside the parameter-default island a capture hit stops blocked at the lambda's own
        // layer instead of being allowed or falling through to an outer same-name binding.
        var result = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "seed",
                useSite,
                FrontendVisibleValueDomain.PARAMETER_DEFAULT
        ));

        assertEquals(FrontendVisibleValueStatus.FOUND_BLOCKED, result.status());
        assertNotNull(result.visibleValue());
        assertEquals(ScopeValueKind.CAPTURE, result.visibleValue().kind());
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
        analyzedInput.analysisData().moduleSkeleton().sourceClassRelations().getFirst().topLevelClassDef().setSuperName("MissingParent");
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
                new FrontendModule("test_module", List.of(unit)),
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

    private static @NotNull LambdaExpression findLambdaExpression(@NotNull Node root) {
        return findNode(root, LambdaExpression.class, _ -> true);
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
