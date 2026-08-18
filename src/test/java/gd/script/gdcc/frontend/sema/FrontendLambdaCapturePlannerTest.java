package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pure-function capture planner tests over hand-built scope graphs.
///
/// These tests freeze capture derivation without opening production inventory: self-shadowing is not captured,
/// class properties stay lexical, first-use order is preserved, and nested transfer stops at a
/// shadowing parent.
class FrontendLambdaCapturePlannerTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void planCapturesOuterParameterAndLocalInFirstUseOrder() throws Exception {
        var graph = outerFunctionGraph();
        var seedDecl = new Object();
        var offsetDecl = new Object();
        graph.functionScope.defineParameter("offset", GdIntType.INT, offsetDecl);
        graph.functionBody.defineLocal("seed", GdIntType.INT, seedDecl);

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(
                        use("seed", graph.lambdaBody),
                        use("offset", graph.lambdaBody),
                        use("seed", graph.lambdaBody)
                )
        );

        assertEquals(2, plan.captures().size());
        assertEquals("seed", plan.captures().getFirst().name());
        assertSame(GdIntType.INT, plan.captures().getFirst().type());
        assertEquals(ScopeValueKind.LOCAL, plan.captures().getFirst().sourceKind());
        assertSame(seedDecl, plan.captures().getFirst().sourceDeclaration());
        assertEquals("offset", plan.captures().getLast().name());
        assertEquals(ScopeValueKind.PARAMETER, plan.captures().getLast().sourceKind());
        assertSame(offsetDecl, plan.captures().getLast().sourceDeclaration());
        assertFalse(plan.capturesSelf());
    }

    @Test
    void planDoesNotCaptureLambdaOwnParameterOrLocal() throws Exception {
        var graph = outerFunctionGraph();
        graph.functionBody.defineLocal("x", GdIntType.INT, new Object());
        var lambdaLocal = new Object();
        graph.lambdaScope.defineParameter("offset", GdIntType.INT, new Object());
        graph.lambdaBody.defineLocal("x", GdIntType.INT, lambdaLocal);

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(use("offset", graph.lambdaBody), use("x", graph.lambdaBody))
        );

        assertTrue(plan.captures().isEmpty());
        assertFalse(FrontendLambdaCapturePlanner.isCaptureFromUse(
                graph.lambdaScope,
                use("x", graph.lambdaBody)
        ));
    }

    @Test
    void planDoesNotCaptureWhenNestedBlockShadowsOuterName() throws Exception {
        var graph = outerFunctionGraph();
        var outerX = new Object();
        graph.functionBody.defineLocal("x", GdIntType.INT, outerX);
        var ifBody = new BlockScope(graph.lambdaBody, BlockScopeKind.IF_BODY);
        var innerX = new Object();
        ifBody.defineLocal("x", GdStringType.STRING, innerX);

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(use("x", ifBody))
        );

        assertTrue(plan.captures().isEmpty());
        assertFalse(FrontendLambdaCapturePlanner.isCaptureFromUse(graph.lambdaScope, use("x", ifBody)));
    }

    @Test
    void planCapturesOuterLocalWhenNestedBlockDoesNotShadow() throws Exception {
        var graph = outerFunctionGraph();
        var outerX = new Object();
        graph.functionBody.defineLocal("x", GdIntType.INT, outerX);
        var ifBody = new BlockScope(graph.lambdaBody, BlockScopeKind.IF_BODY);

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(use("x", ifBody))
        );

        assertEquals(1, plan.captures().size());
        assertSame(outerX, plan.captures().getFirst().sourceDeclaration());
        assertTrue(FrontendLambdaCapturePlanner.isCaptureFromUse(graph.lambdaScope, use("x", ifBody)));
    }

    @Test
    void planDoesNotCaptureClassPropertyOrUnknownName() throws Exception {
        var graph = outerFunctionGraphWithProperty("health");

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(use("health", graph.lambdaBody), use("missing", graph.lambdaBody))
        );

        assertTrue(plan.captures().isEmpty());
    }

    @Test
    void planDoesNotCaptureBlockLocalConstant() throws Exception {
        var graph = outerFunctionGraph();
        graph.functionBody.defineConstant("c", GdIntType.INT, new Object());

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(use("c", graph.lambdaBody))
        );

        assertTrue(plan.captures().isEmpty());
    }

    @Test
    void unannotatedLocalKeepsDeclarationSiteVariant() throws Exception {
        var graph = outerFunctionGraph();
        var decl = new Object();
        graph.functionBody.defineLocal("b", GdVariantType.VARIANT, decl);

        var plan = FrontendLambdaCapturePlanner.planCaptures(
                graph.lambdaScope,
                List.of(use("b", graph.lambdaBody))
        );

        assertEquals(1, plan.captures().size());
        assertSame(GdVariantType.VARIANT, plan.captures().getFirst().type());
        assertSame(decl, plan.captures().getFirst().sourceDeclaration());
    }

    @Test
    void nestedTransferStopsWhenParentShadowsTheName() throws Exception {
        var graph = nestedLambdaGraph();
        var outerX = new Object();
        graph.functionBody.defineLocal("x", GdIntType.INT, outerX);
        var midX = new Object();
        graph.midLambdaBody.defineLocal("x", GdStringType.STRING, midX);

        var innerPlan = FrontendLambdaCapturePlanner.planCaptures(
                graph.innerLambdaScope,
                List.of(use("x", graph.innerLambdaBody))
        );
        assertEquals(1, innerPlan.captures().size());
        assertEquals(ScopeValueKind.LOCAL, innerPlan.captures().getFirst().sourceKind());
        assertSame(midX, innerPlan.captures().getFirst().sourceDeclaration());

        assertNull(FrontendLambdaCapturePlanner.transferredCapture(
                graph.midLambdaScope,
                graph.midLambdaBody,
                innerPlan.captures().getFirst()
        ));
        assertTrue(FrontendLambdaCapturePlanner.transferredCapturesAlongParents(
                List.of(new FrontendLambdaCapturePlanner.ParentLambda(graph.midLambdaScope, graph.midLambdaBody)),
                innerPlan.captures().getFirst()
        ).isEmpty());
    }

    @Test
    void nestedTransferCopiesOuterBindingOntoUnshadowedParents() throws Exception {
        var graph = nestedLambdaGraph();
        var outerX = new Object();
        graph.functionBody.defineLocal("x", GdIntType.INT, outerX);

        var innerPlan = FrontendLambdaCapturePlanner.planCaptures(
                graph.innerLambdaScope,
                List.of(use("x", graph.innerLambdaBody))
        );
        assertSame(outerX, innerPlan.captures().getFirst().sourceDeclaration());

        var transferred = FrontendLambdaCapturePlanner.transferredCapturesAlongParents(
                List.of(new FrontendLambdaCapturePlanner.ParentLambda(graph.midLambdaScope, graph.midLambdaBody)),
                innerPlan.captures().getFirst()
        );
        assertEquals(1, transferred.size());
        assertEquals("x", transferred.getFirst().name());
        assertSame(outerX, transferred.getFirst().sourceDeclaration());
        assertSame(GdIntType.INT, transferred.getFirst().type());
    }

    @Test
    void capturePlanRequiresLeadingSelfWhenCapturesSelf() {
        var self = new LambdaCaptureEntry("self", GdIntType.INT, ScopeValueKind.LOCAL, new Object());
        var seed = new LambdaCaptureEntry("seed", GdIntType.INT, ScopeValueKind.LOCAL, new Object());

        var withSelf = FrontendLambdaCapturePlan.of(List.of(self, seed));
        assertTrue(withSelf.capturesSelf());
        assertEquals("self", withSelf.captures().getFirst().name());
        assertFalse(FrontendLambdaCapturePlan.of(List.of(seed)).capturesSelf());

        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaCapturePlan(List.of(seed), true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaCapturePlan(List.of(self, seed), false)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaCapturePlan(List.of(), true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaCapturePlan(List.of(seed, self), false)
        );
    }

    @Test
    void lambdaCaptureEntryRejectsNonCapturableKindAndBlankName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LambdaCaptureEntry("x", GdIntType.INT, ScopeValueKind.PROPERTY, new Object())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LambdaCaptureEntry(" ", GdIntType.INT, ScopeValueKind.LOCAL, new Object())
        );
    }

    @Test
    void lambdaPlanSamePlanIgnoresAstIdentityAndComparesPayload() {
        var lambda = emptyLambda();
        var enclosing = emptyFunction("run");
        var capture = new LambdaCaptureEntry("seed", GdIntType.INT, ScopeValueKind.LOCAL, new Object());
        var first = new FrontendLambdaPlan(
                lambda,
                "_lambda_0",
                new FrontendLambdaCapturePlan(List.of(capture), false),
                GdVariantType.VARIANT,
                enclosing,
                "Hero"
        );
        var second = new FrontendLambdaPlan(
                lambda,
                "_lambda_0",
                new FrontendLambdaCapturePlan(List.of(capture.withType(GdIntType.INT)), false),
                GdVariantType.VARIANT,
                enclosing,
                "Hero"
        );
        assertTrue(FrontendLambdaPlan.samePlan(first, second));

        var renamed = new FrontendLambdaPlan(
                lambda,
                "_lambda_1",
                first.capturePlan(),
                first.returnType(),
                enclosing,
                "Hero"
        );
        assertFalse(FrontendLambdaPlan.samePlan(first, renamed));
    }

    private static @NotNull FrontendLambdaCapturePlanner.IdentifierUse use(
            @NotNull String name,
            @NotNull BlockScope scope
    ) {
        return new FrontendLambdaCapturePlanner.IdentifierUse(name, scope);
    }

    private static @NotNull LambdaExpression emptyLambda() {
        return new LambdaExpression(null, List.of(), null, new Block(List.of(new PassStatement(RANGE)), RANGE), RANGE);
    }

    private static @NotNull FunctionDeclaration emptyFunction(@NotNull String name) {
        return new FunctionDeclaration(
                name,
                List.of(),
                null,
                false,
                new Block(List.of(new PassStatement(RANGE)), RANGE),
                RANGE
        );
    }

    private static @NotNull OuterGraph outerFunctionGraphWithProperty(@NotNull String propertyName) throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("Hero", "RefCounted");
        ownerClass.addProperty(new gd.script.gdcc.lir.LirPropertyDef(propertyName, GdIntType.INT));
        registry.addGdccClass(ownerClass);
        return finishOuterGraph(registry, ownerClass);
    }

    private static @NotNull OuterGraph outerFunctionGraph() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var ownerClass = new LirClassDef("Hero", "RefCounted");
        registry.addGdccClass(ownerClass);
        return finishOuterGraph(registry, ownerClass);
    }

    private static @NotNull OuterGraph finishOuterGraph(
            @NotNull ClassRegistry registry,
            @NotNull LirClassDef ownerClass
    ) {
        var classScope = new ClassScope(registry, registry, ownerClass);
        var functionScope = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var functionBody = new BlockScope(functionScope, BlockScopeKind.FUNCTION_BODY);
        var lambdaScope = new CallableScope(functionBody, CallableScopeKind.LAMBDA_EXPRESSION);
        var lambdaBody = new BlockScope(lambdaScope, BlockScopeKind.LAMBDA_BODY);
        return new OuterGraph(ownerClass, functionScope, functionBody, lambdaScope, lambdaBody);
    }

    private static @NotNull NestedGraph nestedLambdaGraph() throws Exception {
        var outer = outerFunctionGraph();
        var midLambdaScope = new CallableScope(outer.functionBody, CallableScopeKind.LAMBDA_EXPRESSION);
        var midLambdaBody = new BlockScope(midLambdaScope, BlockScopeKind.LAMBDA_BODY);
        var innerLambdaScope = new CallableScope(midLambdaBody, CallableScopeKind.LAMBDA_EXPRESSION);
        var innerLambdaBody = new BlockScope(innerLambdaScope, BlockScopeKind.LAMBDA_BODY);
        return new NestedGraph(
                outer.functionBody,
                midLambdaScope,
                midLambdaBody,
                innerLambdaScope,
                innerLambdaBody
        );
    }

    private record OuterGraph(
            @NotNull LirClassDef ownerClass,
            @NotNull CallableScope functionScope,
            @NotNull BlockScope functionBody,
            @NotNull CallableScope lambdaScope,
            @NotNull BlockScope lambdaBody
    ) {
    }

    private record NestedGraph(
            @NotNull BlockScope functionBody,
            @NotNull CallableScope midLambdaScope,
            @NotNull BlockScope midLambdaBody,
            @NotNull CallableScope innerLambdaScope,
            @NotNull BlockScope innerLambdaBody
    ) {
    }
}
