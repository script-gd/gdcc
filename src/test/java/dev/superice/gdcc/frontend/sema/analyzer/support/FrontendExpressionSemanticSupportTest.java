package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendExpressionSemanticSupportTest {
    @Test
    void resolveIdentifierExpressionTypeMaterializesCallableValuesAndRejectsBareTypeMeta() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_identifiers.gd",
                """
                        class_name ExpressionSemanticSupportIdentifiers
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping(seed):
                            seed
                            helper
                            Worker
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var seed = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var helper = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var worker = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );

        var seedResult = support.resolveIdentifierExpressionType(seed);
        assertFalse(seedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, seedResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, seedResult.expressionType().publishedType());

        var helperResult = support.resolveIdentifierExpressionType(helper);
        assertFalse(helperResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, helperResult.expressionType().status());
        assertInstanceOf(GdCallableType.class, helperResult.expressionType().publishedType());

        var workerResult = support.resolveIdentifierExpressionType(worker);
        assertFalse(workerResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, workerResult.expressionType().status());
        assertTrue(workerResult.expressionType().detailReason().contains("static route"));
    }

    @Test
    void resolveCallExpressionTypeDistinguishesResolvedBlockedAndUnsupportedCalls() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_calls.gd",
                """
                        class_name ExpressionSemanticSupportCalls
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value
                        
                        func make_cb() -> Callable:
                            return helper
                        
                        static func ping_static(value: int):
                            helper(value)
                        
                        func ping():
                            helper(1)
                            self.make_cb()()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var pingStaticFunction = findFunction(analyzed.ast(), "ping_static");
        var resolvedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var unsupportedDirectCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var blockedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()).expression()
        );

        var unrestrictedSupport = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var staticSupport = createSupport(analyzed, ResolveRestriction.staticContext(), true);
        var publishedResolver = publishedExpressionResolver(analyzed);

        var resolvedResult = unrestrictedSupport.resolveCallExpressionType(
                resolvedBareCall,
                publishedResolver,
                true,
                false
        );
        assertTrue(resolvedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedResult.expressionType().status());
        assertEquals("int", resolvedResult.expressionType().publishedType().getTypeName());

        var blockedResult = staticSupport.resolveCallExpressionType(
                blockedBareCall,
                publishedResolver,
                true,
                false
        );
        assertTrue(blockedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedResult.expressionType().status());
        assertNotNull(blockedResult.expressionType().publishedType());
        assertEquals("int", blockedResult.expressionType().publishedType().getTypeName());

        var unsupportedResult = unrestrictedSupport.resolveCallExpressionType(
                unsupportedDirectCall,
                publishedResolver,
                true,
                false
        );
        assertTrue(unsupportedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedResult.expressionType().status());
        assertTrue(unsupportedResult.expressionType().detailReason().contains("Direct invocation of callable values"));
    }

    @Test
    void resolveSubscriptExpressionTypePublishesResolvedAndDynamicOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_subscript.gd",
                """
                        class_name ExpressionSemanticSupportSubscript
                        extends RefCounted
                        
                        func ping(items: Array[int], lookup: Dictionary[String, int], packed: PackedInt32Array, dynamic_value):
                            items[0]
                            lookup["hp"]
                            packed[0]
                            dynamic_value[0]
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var itemsSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var lookupSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var packedSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var dynamicSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );

        var itemsResult = support.resolveSubscriptExpressionType(itemsSubscript, publishedResolver, false);
        assertTrue(itemsResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, itemsResult.expressionType().status());
        assertEquals("int", itemsResult.expressionType().publishedType().getTypeName());

        var lookupResult = support.resolveSubscriptExpressionType(lookupSubscript, publishedResolver, false);
        assertTrue(lookupResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, lookupResult.expressionType().status());
        assertEquals("int", lookupResult.expressionType().publishedType().getTypeName());

        var packedResult = support.resolveSubscriptExpressionType(packedSubscript, publishedResolver, false);
        assertTrue(packedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, packedResult.expressionType().status());
        assertEquals("int", packedResult.expressionType().publishedType().getTypeName());

        var dynamicResult = support.resolveSubscriptExpressionType(dynamicSubscript, publishedResolver, false);
        assertTrue(dynamicResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.expressionType().publishedType());
    }

    @Test
    void resolveDeferredRoutesKeepDependencyProvenance() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_deferred.gd",
                """
                        class_name ExpressionSemanticSupportDeferred
                        extends RefCounted
                        
                        func ping(items):
                            items = 1
                            1 + missing.payload
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var genericDeferred = assertInstanceOf(
                Expression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getLast()).expression()
        );

        var genericResult = support.resolveExplicitDeferredExpressionType(
                genericDeferred,
                publishedResolver,
                true,
                "generic deferred fallback",
                false
        );
        assertFalse(genericResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, genericResult.expressionType().status());
        assertTrue(genericResult.expressionType().detailReason().contains("chain head"));
    }

    private @NotNull FrontendExpressionSemanticSupport createSupport(
            @NotNull AnalyzedScript analyzed,
            @NotNull ResolveRestriction restriction,
            boolean staticContext
    ) {
        return new FrontendExpressionSemanticSupport(
                analyzed.analysisData().symbolBindings(),
                analyzed.analysisData().scopesByAst(),
                () -> restriction,
                analyzed.classRegistry(),
                () -> new FrontendChainHeadReceiverSupport(
                        analyzed.analysisData(),
                        analyzed.analysisData().scopesByAst(),
                        restriction,
                        staticContext,
                        _ -> null,
                        _ -> null
                )
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver publishedExpressionResolver(
            @NotNull AnalyzedScript analyzed
    ) {
        return (expression, finalizeWindow) -> {
            var published = analyzed.analysisData().expressionTypes().get(expression);
            return Objects.requireNonNull(
                    published,
                    "Expected published expression type for " + expression.getClass().getSimpleName()
            );
        };
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                "test_module",
                List.of(unit),
                classRegistry,
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData, classRegistry);
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).getFirst();
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
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

    private record AnalyzedScript(
            @NotNull Node ast,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
    }
}
