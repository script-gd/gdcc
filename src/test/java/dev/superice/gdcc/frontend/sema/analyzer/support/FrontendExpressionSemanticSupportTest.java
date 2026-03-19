package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
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
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

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
    void resolveRemainingExplicitExpressionRoutesEnumerateDeferredKindsAndRejectParserRecoveryNodes() throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();
        var typeRef = new TypeRef("String", TINY);
        var literal = integerLiteral("1");
        var cases = List.of(
                new RemainingExpressionCase(
                        new BinaryExpression("+", literal, integerLiteral("2"), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Binary operator typing is deferred"
                ),
                new RemainingExpressionCase(
                        new UnaryExpression("-", literal, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Unary operator typing is deferred"
                ),
                new RemainingExpressionCase(
                        new ConditionalExpression(identifier("flag"), integerLiteral("1"), integerLiteral("2"), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Conditional expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new ArrayExpression(List.of(integerLiteral("1")), false, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Array literal typing is deferred"
                ),
                new RemainingExpressionCase(
                        new DictionaryExpression(
                                List.of(new DictEntry(stringLiteral("\"hp\""), integerLiteral("1"), TINY)),
                                false,
                                TINY
                        ),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Dictionary literal typing is deferred"
                ),
                new RemainingExpressionCase(
                        new AwaitExpression(identifier("signal_name"), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Await expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new PreloadExpression(stringLiteral("\"res://icon.svg\""), TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Preload expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new GetNodeExpression("$Camera3D", TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Get-node expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new CastExpression(identifier("value"), typeRef, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Cast expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new TypeTestExpression(identifier("value"), typeRef, false, TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Type-test expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new PatternBindingExpression("captured", TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Pattern binding expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new UnknownExpression("recovery_node", "??", TINY),
                        FrontendExpressionTypeStatus.UNSUPPORTED,
                        "Parser recovery expression 'recovery_node'"
                )
        );

        for (var testCase : cases) {
            var result = support.resolveRemainingExplicitExpressionType(
                    testCase.expression(),
                    nestedResolver,
                    true,
                    false
            );
            assertTrue(result.rootOwnsOutcome(), () -> "expected root-owned outcome for " + testCase.expression());
            assertEquals(testCase.status(), result.expressionType().status());
            assertTrue(
                    result.expressionType().detailReason().contains(testCase.reasonFragment()),
                    () -> "expected detail reason to contain '" + testCase.reasonFragment() + "' but got '"
                            + result.expressionType().detailReason() + "'"
            );
        }
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

        var genericResult = support.resolveRemainingExplicitExpressionType(
                genericDeferred,
                publishedResolver,
                true,
                false
        );
        assertFalse(genericResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, genericResult.expressionType().status());
        assertTrue(genericResult.expressionType().detailReason().contains("chain head"));
    }

    @Test
    void selectCallableOverloadReportsAmbiguousAndEmptyOverloadSets() throws Exception {
        var support = newBareSupport();
        var ambiguous = List.of(
                newCallable("helper", GdIntType.INT, GdIntType.INT),
                newCallable("helper", GdStringType.STRING, GdIntType.INT)
        );

        var ambiguousSelection = support.selectCallableOverload(ambiguous, List.of(GdIntType.INT));
        assertTrue(ambiguousSelection.selected() == null);
        assertTrue(ambiguousSelection.detailReason().contains("Ambiguous bare call overload"));

        var emptySelection = support.selectCallableOverload(List.of(), List.of(GdIntType.INT));
        assertTrue(emptySelection.selected() == null);
        assertEquals("Bare call resolves to an empty overload set", emptySelection.detailReason());
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
                () -> null,
                analyzed.classRegistry(),
                () -> new FrontendChainHeadReceiverSupport(
                        analyzed.analysisData(),
                        analyzed.analysisData().scopesByAst(),
                        restriction,
                        staticContext,
                        null,
                        _ -> null,
                        _ -> null
                )
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport newBareSupport() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        return new FrontendExpressionSemanticSupport(
                analysisData.symbolBindings(),
                analysisData.scopesByAst(),
                ResolveRestriction::instanceContext,
                () -> null,
                classRegistry,
                () -> new FrontendChainHeadReceiverSupport(
                        analysisData,
                        analysisData.scopesByAst(),
                        ResolveRestriction.instanceContext(),
                        false,
                        null,
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

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver resolvedVariantResolver() {
        return (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdVariantType.VARIANT);
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

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull dev.superice.gdparser.frontend.ast.LiteralExpression integerLiteral(
            @NotNull String sourceText
    ) {
        return new dev.superice.gdparser.frontend.ast.LiteralExpression("integer", sourceText, TINY);
    }

    private static @NotNull dev.superice.gdparser.frontend.ast.LiteralExpression stringLiteral(
            @NotNull String sourceText
    ) {
        return new dev.superice.gdparser.frontend.ast.LiteralExpression("string", sourceText, TINY);
    }

    private static @NotNull LirFunctionDef newCallable(
            @NotNull String name,
            @NotNull dev.superice.gdcc.type.GdType returnType,
            @NotNull dev.superice.gdcc.type.GdType... parameterTypes
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        for (var index = 0; index < parameterTypes.length; index++) {
            function.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, function));
        }
        return function;
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

    private record RemainingExpressionCase(
            @NotNull Expression expression,
            @NotNull FrontendExpressionTypeStatus status,
            @NotNull String reasonFragment
    ) {
    }
}
