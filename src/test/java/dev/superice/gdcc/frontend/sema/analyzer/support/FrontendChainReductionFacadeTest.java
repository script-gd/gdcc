package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.CallableScopeKind;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendChainReductionFacadeTest {
    private static final @NotNull Range TINY = new Range(0, 0, new Point(0, 0), new Point(0, 0));

    @Test
    void reduceShouldCacheSuccessfulReductionAcrossRepeatedLookups() throws Exception {
        var context = newTestContext();
        var worker = identifier("worker");
        defineWorkerLocal(context, worker);
        var facade = newFacade(context);
        var chain = chain(worker, property("payload"));

        var first = facade.reduce(chain);
        var second = facade.reduce(chain);

        assertTrue(first.computedNow());
        assertNotNull(first.result());
        assertFalse(second.computedNow());
        assertSame(first.result(), second.result());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, first.result().finalReceiver().status());
    }

    @Test
    void reduceShouldCacheNestedAttributeBaseWhenOuterChainRequestsIt() throws Exception {
        var context = newTestContext();
        var worker = identifier("worker");
        defineWorkerLocal(context, worker);
        var facade = newFacade(context);
        var inner = chain(worker, property("payload"));
        var outer = chain(inner, property("length"));

        var outerReduction = facade.reduce(outer);
        var nestedCached = facade.reduce(inner);

        assertTrue(outerReduction.computedNow());
        assertNotNull(outerReduction.result());
        assertFalse(nestedCached.computedNow());
        assertNotNull(nestedCached.result());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, nestedCached.result().finalReceiver().status());
    }

    @Test
    void reduceShouldCacheMissingHeadReceiverAsEmptyResult() throws Exception {
        var context = newTestContext();
        var facade = newFacade(context);
        var missing = chain(identifier("ghost"), property("payload"));

        var first = facade.reduce(missing);
        var second = facade.reduce(missing);

        assertTrue(first.computedNow());
        assertNull(first.result());
        assertFalse(second.computedNow());
        assertNull(second.result());
    }

    private static @NotNull FrontendChainReductionFacade newFacade(@NotNull TestContext context) {
        return new FrontendChainReductionFacade(
                context.analysisData(),
                context.analysisData().scopesByAst(),
                ResolveRestriction::unrestricted,
                () -> false,
                context.registry(),
                (expression, finalizeWindow) -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                        "unexpected expression type lookup for " + expression.getClass().getSimpleName()
                )
        );
    }

    private static void defineWorkerLocal(
            @NotNull TestContext context,
            @NotNull IdentifierExpression workerIdentifier
    ) {
        var workerClass = new LirClassDef("Worker", "Object");
        workerClass.addProperty(new LirPropertyDef("payload", GdStringType.STRING));
        context.registry().addGdccClass(workerClass);
        context.bodyScope().defineLocal("worker", new GdObjectType("Worker"), workerIdentifier);
        context.analysisData().symbolBindings().put(
                workerIdentifier,
                new FrontendBinding("worker", FrontendBindingKind.LOCAL_VAR, workerIdentifier)
        );
        context.analysisData().scopesByAst().put(workerIdentifier, context.bodyScope());
    }

    private static @NotNull TestContext newTestContext() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var heroClass = new LirClassDef("Hero", "Object");
        registry.addGdccClass(heroClass);
        var classScope = new ClassScope(registry, registry, heroClass);
        var callableScope = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var bodyScope = new BlockScope(callableScope, BlockScopeKind.FUNCTION_BODY);
        return new TestContext(analysisData, registry, classScope, bodyScope);
    }

    private static @NotNull AttributeExpression chain(@NotNull Expression base, @NotNull AttributeStep... steps) {
        return new AttributeExpression(base, List.of(steps), TINY);
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull AttributePropertyStep property(@NotNull String name) {
        return new AttributePropertyStep(name, TINY);
    }

    private record TestContext(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry registry,
            @NotNull ClassScope classScope,
            @NotNull BlockScope bodyScope
    ) {
    }
}
