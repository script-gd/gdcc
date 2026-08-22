package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.patch.FrontendLambdaResolutionPatch;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lambda suite-resolution contract tests.
///
/// Anchors both directions: recorded lambdas resolve through the nested trigger and publish the
/// first complete `FrontendLambdaPlan` with declaration-site capture types mirrored onto the
/// `CallableScope` bindings, while unrecorded lambdas (property initializers)
/// stay fail-closed and a diverging re-publish of the same lambda must conflict.
class FrontendLambdaSuiteResolutionTest {
    @Test
    void nestedResolvePublishesPlanWithDeclarationSiteCaptureTypes() throws Exception {
        var analysisData = analyze("lambda_suite_basic.gd", """
                class_name LambdaSuiteBasic
                extends Node
                
                func ping(seed: int):
                    var a := 1
                    var b = 1
                    var cb := func():
                        return a + b + seed
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var lambdaScope = assertInstanceOf(
                CallableScope.class,
                analysisData.analysisData().scopesByAst().get(lambda)
        );

        var plan = analysisData.analysisData().lambdaPlans().get(lambda);

        assertNotNull(plan);
        assertEquals("_lambda_0", plan.syntheticName());
        assertSame(pingFunction, plan.enclosingCallable());
        assertEquals("LambdaSuiteBasic", plan.owningClassCanonicalName());
        assertFalse(plan.capturesSelf());
        // Capture order is frozen by first source appearance inside the lambda body.
        assertEquals(List.of("a", "b", "seed"), plan.captures().stream().map(LambdaCaptureEntry::name).toList());
        // `var a := 1` reads the flushed stabilization update; `var b = 1` never infers; the
        // explicit parameter annotation publishes its declared type.
        var aCapture = plan.captures().get(0);
        assertEquals(GdIntType.INT, aCapture.type());
        assertEquals(ScopeValueKind.LOCAL, aCapture.sourceKind());
        var bCapture = plan.captures().get(1);
        assertEquals(GdVariantType.VARIANT, bCapture.type());
        assertEquals(ScopeValueKind.LOCAL, bCapture.sourceKind());
        var seedCapture = plan.captures().get(2);
        assertEquals(GdIntType.INT, seedCapture.type());
        assertEquals(ScopeValueKind.PARAMETER, seedCapture.sourceKind());
        assertSame(pingFunction.parameters().getFirst(), seedCapture.sourceDeclaration());
        // The CallableScope capture bindings are reset to the same declaration-site types.
        assertEquals(GdIntType.INT, Objects.requireNonNull(lambdaScope.resolveValueHere("a")).type());
        assertEquals(GdVariantType.VARIANT, Objects.requireNonNull(lambdaScope.resolveValueHere("b")).type());
        assertEquals(GdIntType.INT, Objects.requireNonNull(lambdaScope.resolveValueHere("seed")).type());
        // Use sites inside the lambda body bind to the CAPTURE slots.
        for (var name : List.of("a", "b", "seed")) {
            var useSite = findNode(lambda.body(), IdentifierExpression.class, id -> id.name().equals(name));
            var binding = analysisData.analysisData().symbolBindings().get(useSite);
            assertNotNull(binding, name);
            assertEquals(FrontendBindingKind.CAPTURE, binding.kind(), name);
        }
        assertTrue(analysisData.diagnostics().asList().isEmpty());
    }

    @Test
    void recordedLambdaPublishesCallableExpressionTypeAlongsidePlan() throws Exception {
        var analysisData = analyze("lambda_expression_type.gd", """
                class_name LambdaExpressionType
                extends RefCounted
                
                func ping():
                    var cb := func(x: int):
                        return x
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);

        // The nested-resolved plan (LAMBDA_RESOLUTION owner) and the callable
        // expression fact (EXPR_TYPE owner) coexist for the same recorded lambda node.
        assertNotNull(analysisData.analysisData().lambdaPlans().get(lambda));
        var expressionType = analysisData.analysisData().expressionTypes().get(lambda);
        assertNotNull(expressionType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
        assertEquals("Callable", expressionType.publishedType().getTypeName());

        // Silent stabilization never resolves the lambda initializer, so the `:=` slot keeps its
        // inventory Variant; only a non-silent write-back after nested resolve could refine it.
        var bodyScope = assertInstanceOf(
                BlockScope.class,
                analysisData.analysisData().scopesByAst().get(pingFunction.body())
        );
        assertEquals(GdVariantType.VARIANT, Objects.requireNonNull(bodyScope.resolveValue("cb")).type());

        assertFalse(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_expression_route")
        ));
    }

    @Test
    void lambdaContainingMatchKeepsCallableTypeWithoutUnsupportedMatchDiagnostics() throws Exception {
        var analysisData = analyze("lambda_match_inside.gd", """
                class_name LambdaMatchInside
                extends RefCounted
                
                func ping():
                    var cb := func():
                        match 1:
                            1:
                                pass
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);

        // Match inside a recorded lambda is shared-semantic supported: the plan and Callable
        // expression fact stay published, and match no longer emits unsupported inventory/binding.
        assertNotNull(analysisData.analysisData().lambdaPlans().get(lambda));
        var expressionType = analysisData.analysisData().expressionTypes().get(lambda);
        assertNotNull(expressionType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
        assertEquals("Callable", expressionType.publishedType().getTypeName());
        var matchStatement = findNode(lambda.body(), MatchStatement.class, _ -> true);
        assertNotNull(analysisData.analysisData().matchPlans().get(matchStatement));
        assertFalse(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_variable_inventory_subtree")
        ));
        assertFalse(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_binding_subtree")
        ));
        assertFalse(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_expression_route")
        ));
    }

    @Test
    void lambdaInContainerCallAndReturnPositionsAllResolve() throws Exception {
        var analysisData = analyze("lambda_positions.gd", """
                class_name LambdaPositions
                extends RefCounted
                
                func foo(c):
                    return c
                
                func ping():
                    var arr = [func(): return 1]
                    var cb = foo(func(): return 2)
                    return func(): return 3
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambdas = new ArrayList<LambdaExpression>();
        collectMatchingNodes(pingFunction.body(), LambdaExpression.class, _ -> true, lambdas);

        // Interface traversal reaches lambdas in every expression position (container element,
        // call argument, return value); each one is recorded, nested-resolved, and publishes
        // the unparameterized Callable type.
        assertEquals(3, lambdas.size());
        for (var lambda : lambdas) {
            assertNotNull(analysisData.analysisData().lambdaPlans().get(lambda));
            var expressionType = analysisData.analysisData().expressionTypes().get(lambda);
            assertNotNull(expressionType);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
            assertEquals("Callable", expressionType.publishedType().getTypeName());
        }
        assertTrue(analysisData.diagnostics().asList().isEmpty());
    }

    @Test
    void nestedResolvePublishesSelfCaptureWithOwningClassInstanceType() throws Exception {
        var analysisData = analyze("lambda_suite_self.gd", """
                class_name LambdaSuiteSelf
                extends Node
                
                var hp: int = 0
                
                func ping():
                    var cb := func():
                        return hp
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var lambdaScope = assertInstanceOf(
                CallableScope.class,
                analysisData.analysisData().scopesByAst().get(lambda)
        );

        var plan = analysisData.analysisData().lambdaPlans().get(lambda);

        assertNotNull(plan);
        assertTrue(plan.capturesSelf());
        var selfCapture = plan.captures().getFirst();
        assertEquals("self", selfCapture.name());
        assertEquals(new GdObjectType("LambdaSuiteSelf"), selfCapture.type());
        assertEquals(ScopeValueKind.PARAMETER, selfCapture.sourceKind());
        assertSame(
                pingFunction,
                selfCapture.sourceDeclaration(),
                "self capture keeps the enclosing instance callable as its source declaration"
        );
        var scopeSelf = Objects.requireNonNull(lambdaScope.resolveValueHere("self"));
        assertEquals(new GdObjectType("LambdaSuiteSelf"), scopeSelf.type());
        assertTrue(analysisData.diagnostics().asList().isEmpty());
    }

    @Test
    void nestedLambdaCaptureTransfersWithFrozenOuterCaptureType() throws Exception {
        var analysisData = analyze("lambda_suite_nested.gd", """
                class_name LambdaSuiteNested
                extends Node
                
                func ping(seed: int):
                    var cb := func():
                        var inner := func():
                            return seed
                        return inner
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var outerLambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var innerLambda = findNode(outerLambda.body(), LambdaExpression.class, _ -> true);

        var outerPlan = analysisData.analysisData().lambdaPlans().get(outerLambda);
        var innerPlan = analysisData.analysisData().lambdaPlans().get(innerLambda);

        assertNotNull(outerPlan);
        assertNotNull(innerPlan);
        // Synthetic names follow resolution (source appearance) order: outer first, then inner.
        assertEquals("_lambda_0", outerPlan.syntheticName());
        assertEquals("_lambda_1", innerPlan.syntheticName());
        assertSame(pingFunction, outerPlan.enclosingCallable());
        assertSame(pingFunction, innerPlan.enclosingCallable());
        assertEquals(1, outerPlan.captures().size());
        assertEquals(1, innerPlan.captures().size());
        var outerCapture = outerPlan.captures().getFirst();
        assertEquals("seed", outerCapture.name());
        assertEquals(ScopeValueKind.PARAMETER, outerCapture.sourceKind());
        assertEquals(GdIntType.INT, outerCapture.type());
        var transferredCapture = innerPlan.captures().getFirst();
        assertEquals("seed", transferredCapture.name());
        // The inner capture transfers through the outer lambda's own CAPTURE slot (kind CAPTURE),
        // shares the original declaration identity, and freezes the outer capture's filled type.
        assertEquals(ScopeValueKind.CAPTURE, transferredCapture.sourceKind());
        assertEquals(GdIntType.INT, transferredCapture.type());
        assertSame(outerCapture.sourceDeclaration(), transferredCapture.sourceDeclaration());
        assertTrue(analysisData.diagnostics().asList().isEmpty());
    }

    @Test
    void forIteratorCaptureUsesFlushedIterationRefinement() throws Exception {
        var analysisData = analyze("lambda_suite_for_iterator.gd", """
                class_name LambdaSuiteForIterator
                extends Node
                
                func ping(items: Array[int]):
                    for item in items:
                        var cb := func():
                            return item
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var forStatement = findNode(pingFunction.body(), ForStatement.class, _ -> true);
        var lambda = findNode(forStatement.body(), LambdaExpression.class, _ -> true);

        var plan = analysisData.analysisData().lambdaPlans().get(lambda);

        assertNotNull(plan);
        assertEquals(1, plan.captures().size());
        var iteratorCapture = plan.captures().getFirst();
        assertEquals("item", iteratorCapture.name());
        assertEquals(ScopeValueKind.LOCAL, iteratorCapture.sourceKind());
        // The inventory baseline is Variant; the capture freezes the for-iteration refinement.
        assertEquals(GdIntType.INT, iteratorCapture.type());
        assertSame(forStatement, iteratorCapture.sourceDeclaration());
        assertTrue(analysisData.diagnostics().asList().isEmpty());
    }

    @Test
    void divergingRepublishOfSameLambdaConflictsAndKeepsStablePlan() throws Exception {
        var analysisData = analyze("lambda_plan_conflict.gd", """
                class_name LambdaPlanConflict
                extends Node
                
                func ping(seed: int):
                    var cb := func():
                        return seed
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var published = analysisData.analysisData().lambdaPlans().get(lambda);
        assertNotNull(published);

        var diverging = new FrontendLambdaPlan(
                lambda,
                "_lambda_99",
                published.capturePlan(),
                published.returnType(),
                published.enclosingCallable(),
                published.owningClassCanonicalName()
        );
        var divergingTable = new FrontendAstSideTable<FrontendLambdaPlan>();
        divergingTable.put(lambda, diverging);

        assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.analysisData().applyPatch(new FrontendLambdaResolutionPatch(divergingTable))
        );
        assertSame(published, analysisData.analysisData().lambdaPlans().get(lambda));

        // Re-publishing a logically equal payload is the idempotent merge case and must pass.
        var equal = new FrontendLambdaPlan(
                lambda,
                published.syntheticName(),
                published.capturePlan(),
                published.returnType(),
                published.enclosingCallable(),
                published.owningClassCanonicalName()
        );
        var equalTable = new FrontendAstSideTable<FrontendLambdaPlan>();
        equalTable.put(lambda, equal);
        analysisData.analysisData().applyPatch(new FrontendLambdaResolutionPatch(equalTable));
        assertSame(published, analysisData.analysisData().lambdaPlans().get(lambda));
    }

    @Test
    void propertyInitializerLambdaStaysUnrecordedAndFailClosed() throws Exception {
        var analysisData = analyze("lambda_property_initializer.gd", """
                class_name LambdaPropertyInitializer
                extends Node
                
                var cb = func():
                    return 1
                """);
        var lambda = findNode(analysisData.unit().ast(), LambdaExpression.class, _ -> true);

        assertNull(analysisData.analysisData().lambdaPlans().get(lambda));
        assertTrue(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_binding_subtree")
                        && diagnostic.message().contains("lambda subtree")
        ));
        assertTrue(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_chain_route")
                        && diagnostic.message().contains("lambda subtree")
        ));
        // Unrecorded lambdas keep the unsupported expression route with its diagnostic (§3.3).
        assertTrue(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_expression_route")
        ));
    }

    @Test
    void lambdaInsideMatchSectionIsRecordedAndCapturesOuterChoice() throws Exception {
        var analysisData = analyze("lambda_match_boundary.gd", """
                class_name LambdaMatchBoundary
                extends Node
                
                func ping(choice):
                    match choice:
                        0:
                            var cb := func():
                                return choice
                """);
        var lambda = findNode(analysisData.unit().ast(), LambdaExpression.class, _ -> true);

        var plan = analysisData.analysisData().lambdaPlans().get(lambda);
        assertNotNull(plan);
        assertEquals(1, plan.captures().size());
        assertEquals("choice", plan.captures().getFirst().name());
        assertFalse(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_variable_inventory_subtree")
        ));
        assertFalse(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_binding_subtree")
        ));
    }

    @Test
    void captureDeclaredAfterLambdaStatementIsRegisteredButUseSiteStaysFiltered() throws Exception {
        var analysisData = analyze("lambda_capture_after_use.gd", """
                class_name LambdaCaptureAfterUse
                extends Node
                
                func ping():
                    var cb := func():
                        return late
                    var late := 1
                """);
        var pingFunction = findFunction(analysisData.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var lateUseSite = findNode(lambda.body(), IdentifierExpression.class, id -> id.name().equals("late"));

        var plan = analysisData.analysisData().lambdaPlans().get(lambda);

        // The planner may register the name, but the declaration is not visible at the lambda
        // statement: no stabilization update exists yet, so the inventory baseline is frozen.
        assertNotNull(plan);
        assertEquals(1, plan.captures().size());
        assertEquals(GdVariantType.VARIANT, plan.captures().getFirst().type());
        // The use site itself stays fail-closed through the ordinary declaration-after-use route:
        // it binds UNKNOWN (never CAPTURE) and keeps the source-level binding diagnostic.
        var lateBinding = analysisData.analysisData().symbolBindings().get(lateUseSite);
        assertNotNull(lateBinding);
        assertEquals(FrontendBindingKind.UNKNOWN, lateBinding.kind());
        assertTrue(analysisData.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.binding")
        ));
    }

    private static @NotNull AnalyzedLambdaInput analyze(
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
        return new AnalyzedLambdaInput(unit, analysisData, diagnostics.snapshot());
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
        if (nodeType.isInstance(node) && predicate.test(nodeType.cast(node))) {
            matches.add(nodeType.cast(node));
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedLambdaInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
    }
}
