package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.CallableScopeKind;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendChainHeadReceiverSupportTest {
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void resolveHeadReceiverShouldResolveValueTypeMetaAndLiteralHeads() throws Exception {
        var context = newTestContext();
        var workerValue = identifier("worker");
        var workerType = identifier("Worker");
        var literal = new LiteralExpression("string", "\"hi\"", TINY);

        context.bodyScope().defineLocal("worker", new GdObjectType("Worker"), workerValue);
        context.classScope().defineTypeMeta(innerTypeMeta("Hero__sub__Worker", "Worker"));
        context.analysisData().symbolBindings().put(workerValue, new FrontendBinding("worker", FrontendBindingKind.LOCAL_VAR, workerValue));
        context.analysisData().symbolBindings().put(workerType, new FrontendBinding("Worker", FrontendBindingKind.TYPE_META, null));
        context.analysisData().scopesByAst().put(workerValue, context.bodyScope());
        context.analysisData().scopesByAst().put(workerType, context.bodyScope());

        var support = newSupport(context, ResolveRestriction.unrestricted(), false);

        var valueReceiver = support.resolveHeadReceiver(workerValue);
        assertNotNull(valueReceiver);
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, valueReceiver.status());
        assertEquals(FrontendReceiverKind.INSTANCE, valueReceiver.receiverKind());
        assertEquals(new GdObjectType("Worker"), valueReceiver.receiverType());

        var typeReceiver = support.resolveHeadReceiver(workerType);
        assertNotNull(typeReceiver);
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, typeReceiver.status());
        assertEquals(FrontendReceiverKind.TYPE_META, typeReceiver.receiverKind());
        assertEquals(new GdObjectType("Hero__sub__Worker"), typeReceiver.receiverType());
        assertEquals("Worker", typeReceiver.receiverTypeMeta().sourceName());
        assertEquals("Hero__sub__Worker", typeReceiver.receiverTypeMeta().displayName());

        var literalReceiver = support.resolveHeadReceiver(literal);
        assertNotNull(literalReceiver);
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, literalReceiver.status());
        assertEquals(FrontendReceiverKind.INSTANCE, literalReceiver.receiverKind());
        assertEquals(GdStringType.STRING, literalReceiver.receiverType());
    }

    @Test
    void resolveHeadReceiverShouldFailFastWhenTypeMetaResolutionStartsWithoutPublishedModuleSkeleton() throws Exception {
        var context = newTestContext(false);
        var workerType = identifier("Worker");
        context.classScope().defineTypeMeta(innerTypeMeta("Hero__sub__Worker", "Worker"));
        context.analysisData().symbolBindings().put(workerType, new FrontendBinding("Worker", FrontendBindingKind.TYPE_META, null));
        context.analysisData().scopesByAst().put(workerType, context.bodyScope());

        var support = newSupport(context, ResolveRestriction.unrestricted(), false);
        var error = assertThrows(IllegalStateException.class, () -> support.resolveHeadReceiver(workerType));

        assertEquals("moduleSkeleton has not been published yet", error.getMessage());
    }

    @Test
    void resolveHeadReceiverShouldBlockSelfInStaticContext() throws Exception {
        var context = newTestContext();
        var selfExpression = new SelfExpression(TINY);
        context.analysisData().scopesByAst().put(selfExpression, context.bodyScope());

        var receiver = newSupport(context, ResolveRestriction.staticContext(), true).resolveHeadReceiver(selfExpression);

        assertNotNull(receiver);
        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, receiver.status());
        assertEquals(FrontendReceiverKind.INSTANCE, receiver.receiverKind());
        assertEquals(new GdObjectType("Hero"), receiver.receiverType());
        assertTrue(receiver.detailReason().contains("static context"));
    }

    @Test
    void resolvePublishedValueAndTypeMetaShouldBecomeUnsupportedWhenScopeIsMissing() throws Exception {
        var context = newTestContext();
        var workerValue = identifier("worker");
        var workerType = identifier("Worker");
        context.analysisData().symbolBindings().put(workerValue, new FrontendBinding("worker", FrontendBindingKind.LOCAL_VAR, null));
        context.analysisData().symbolBindings().put(workerType, new FrontendBinding("Worker", FrontendBindingKind.TYPE_META, null));

        var support = newSupport(context, ResolveRestriction.unrestricted(), false);
        var valueReceiver = support.resolveValueReceiver(workerValue);
        var typeReceiver = support.resolveTypeMetaReceiver(workerType);

        assertEquals(FrontendChainReductionHelper.Status.UNSUPPORTED, valueReceiver.status());
        assertNull(valueReceiver.receiverType());
        assertTrue(valueReceiver.detailReason().contains("skipped subtree"));
        assertEquals(FrontendChainReductionHelper.Status.UNSUPPORTED, typeReceiver.status());
        assertNull(typeReceiver.receiverTypeMeta());
        assertTrue(typeReceiver.detailReason().contains("skipped subtree"));
    }

    @Test
    void resolveHeadReceiverShouldFailInsteadOfReturningNullForMissingOrUnknownBindings() throws Exception {
        var context = newTestContext();
        var missing = identifier("ghost");
        var unknown = identifier("mystery");
        context.analysisData().symbolBindings().put(unknown, new FrontendBinding("mystery", FrontendBindingKind.UNKNOWN, null));

        var support = newSupport(context, ResolveRestriction.unrestricted(), false);
        var missingReceiver = support.resolveHeadReceiver(missing);
        var unknownReceiver = support.resolveHeadReceiver(unknown);

        assertNotNull(missingReceiver);
        assertEquals(FrontendChainReductionHelper.Status.FAILED, missingReceiver.status());
        assertTrue(missingReceiver.detailReason().contains("No published chain-head binding fact"));

        assertNotNull(unknownReceiver);
        assertEquals(FrontendChainReductionHelper.Status.FAILED, unknownReceiver.status());
        assertTrue(unknownReceiver.detailReason().contains("does not resolve to a published value or type-meta receiver"));
    }

    @Test
    void resolveHeadReceiverShouldFailWhenPublishedBindingCannotBeRecoveredFromCurrentScope() throws Exception {
        var context = newTestContext();
        var missingValue = identifier("worker");
        var missingTypeMeta = identifier("Worker");
        context.analysisData().symbolBindings().put(missingValue, new FrontendBinding("worker", FrontendBindingKind.LOCAL_VAR, null));
        context.analysisData().symbolBindings().put(missingTypeMeta, new FrontendBinding("Worker", FrontendBindingKind.TYPE_META, null));
        context.analysisData().scopesByAst().put(missingValue, context.bodyScope());
        context.analysisData().scopesByAst().put(missingTypeMeta, context.bodyScope());

        var support = newSupport(context, ResolveRestriction.unrestricted(), false);
        var valueReceiver = support.resolveHeadReceiver(missingValue);
        var typeReceiver = support.resolveHeadReceiver(missingTypeMeta);

        assertNotNull(valueReceiver);
        assertEquals(FrontendChainReductionHelper.Status.FAILED, valueReceiver.status());
        assertTrue(valueReceiver.detailReason().contains("no longer visible"));

        assertNotNull(typeReceiver);
        assertEquals(FrontendChainReductionHelper.Status.FAILED, typeReceiver.status());
        assertTrue(typeReceiver.detailReason().contains("no longer visible"));
    }

    @Test
    void resolveHeadReceiverShouldDelegateNestedAttributeAndFallbackCallbacks() throws Exception {
        var context = newTestContext();
        var nestedAttribute = new AttributeExpression(
                identifier("seed"),
                List.of(new AttributePropertyStep("value", TINY)),
                TINY
        );
        var callExpression = new CallExpression(identifier("make_worker"), List.of(), TINY);
        var nestedReceiver = FrontendChainReductionHelper.ReceiverState.resolvedInstance(GdIntType.INT);
        var fallbackReceiver = FrontendChainReductionHelper.ReceiverState.dynamic(
                "fallback expression routes through runtime-dynamic semantics"
        );
        var support = newSupport(
                context,
                ResolveRestriction.unrestricted(),
                false,
                attributeExpression -> nestedReceiver,
                expression -> fallbackReceiver
        );

        assertEquals(nestedReceiver, support.resolveHeadReceiver(nestedAttribute));
        assertEquals(fallbackReceiver, support.resolveHeadReceiver(callExpression));
    }

    @Test
    void resolveHeadReceiverShouldMaterializeCallableForPublishedFunctionLikeBindings() throws Exception {
        var context = newTestContext();
        var bareMethod = identifier("build");
        var buildFunction = new LirFunctionDef("build");
        buildFunction.setStatic(true);
        buildFunction.setReturnType(GdIntType.INT);
        context.classScope().defineFunction(buildFunction);
        context.analysisData().symbolBindings().put(bareMethod, new FrontendBinding("build", FrontendBindingKind.STATIC_METHOD, null));
        context.analysisData().scopesByAst().put(bareMethod, context.bodyScope());

        var receiver = newSupport(context, ResolveRestriction.unrestricted(), false).resolveHeadReceiver(bareMethod);

        assertNotNull(receiver);
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, receiver.status());
        assertEquals(FrontendReceiverKind.INSTANCE, receiver.receiverKind());
        assertEquals(new GdCallableType(), receiver.receiverType());
        assertNull(receiver.detailReason());
    }

    @Test
    void resolveHeadReceiverShouldPreserveBlockedAndFailedCallableReceivers() throws Exception {
        var context = newTestContext();
        var blockedHelper = identifier("helper");
        var missingHelper = identifier("missing");
        var helperFunction = new LirFunctionDef("helper");
        helperFunction.setReturnType(GdIntType.INT);
        context.classScope().defineFunction(helperFunction);
        context.analysisData().symbolBindings().put(
                blockedHelper,
                new FrontendBinding("helper", FrontendBindingKind.METHOD, null)
        );
        context.analysisData().symbolBindings().put(
                missingHelper,
                new FrontendBinding("missing", FrontendBindingKind.METHOD, null)
        );
        context.analysisData().scopesByAst().put(blockedHelper, context.bodyScope());
        context.analysisData().scopesByAst().put(missingHelper, context.bodyScope());

        var blockedReceiver = newSupport(context, ResolveRestriction.staticContext(), true).resolveHeadReceiver(blockedHelper);
        var failedReceiver = newSupport(context, ResolveRestriction.unrestricted(), false).resolveHeadReceiver(missingHelper);

        assertNotNull(blockedReceiver);
        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, blockedReceiver.status());
        assertEquals(FrontendReceiverKind.INSTANCE, blockedReceiver.receiverKind());
        assertEquals(new GdCallableType(), blockedReceiver.receiverType());
        assertTrue(blockedReceiver.detailReason().contains("not accessible"));

        assertNotNull(failedReceiver);
        assertEquals(FrontendChainReductionHelper.Status.FAILED, failedReceiver.status());
        assertNull(failedReceiver.receiverType());
        assertTrue(failedReceiver.detailReason().contains("no longer visible"));
    }

    private static @NotNull FrontendChainHeadReceiverSupport newSupport(
            @NotNull TestContext context,
            @NotNull ResolveRestriction restriction,
            boolean staticContext
    ) {
        return newSupport(
                context,
                restriction,
                staticContext,
                _ -> {
                    throw new AssertionError("unexpected nested attribute receiver lookup");
                },
                expression -> {
                    throw new AssertionError(
                            "unexpected fallback receiver lookup for " + expression.getClass().getSimpleName()
                    );
                }
        );
    }

    private static @NotNull FrontendChainHeadReceiverSupport newSupport(
            @NotNull TestContext context,
            @NotNull ResolveRestriction restriction,
            boolean staticContext,
            @NotNull FrontendChainHeadReceiverSupport.NestedAttributeReceiverResolver nestedResolver,
            @NotNull FrontendChainHeadReceiverSupport.FallbackExpressionReceiverResolver fallbackResolver
    ) {
        return new FrontendChainHeadReceiverSupport(
                context.analysisData(),
                context.analysisData().scopesByAst(),
                restriction,
                staticContext,
                null,
                nestedResolver,
                fallbackResolver
        );
    }

    private static @NotNull TestContext newTestContext() throws Exception {
        return newTestContext(true);
    }

    private static @NotNull TestContext newTestContext(boolean publishModuleSkeleton) throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        if (publishModuleSkeleton) {
            analysisData.updateModuleSkeleton(
                    new FrontendModuleSkeleton(
                            "test_module",
                            List.of(),
                            Map.of(),
                            new DiagnosticSnapshot(List.of())
                    )
            );
        }
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var heroClass = new LirClassDef("Hero", "Object");
        registry.addGdccClass(heroClass);
        var classScope = new ClassScope(registry, registry, heroClass);
        var callableScope = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var bodyScope = new BlockScope(callableScope, BlockScopeKind.FUNCTION_BODY);
        return new TestContext(analysisData, classScope, bodyScope);
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull ScopeTypeMeta innerTypeMeta(
            @NotNull String canonicalName,
            @NotNull String sourceName
    ) {
        return new ScopeTypeMeta(
                canonicalName,
                sourceName,
                new GdObjectType(canonicalName),
                ScopeTypeMetaKind.GDCC_CLASS,
                canonicalName,
                false
        );
    }

    private record TestContext(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassScope classScope,
            @NotNull BlockScope bodyScope
    ) {
    }
}
