package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendChainReductionHelperTest {
    private static final Range TINY = new Range(0, 0, new Point(0, 0), new Point(0, 0));

    @Test
    void reduceBuildsResolvedPropertyTraceAcrossConsecutiveSteps() {
        var worker = newClass("Worker");
        worker.addProperty(new LirPropertyDef("payload", GdStringType.STRING));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(worker));
        var chain = chain(identifier("worker"), property("payload"), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                noExpressionTypes()
        ));

        assertEquals(2, result.stepTraces().size());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.INSTANCE_PROPERTY, result.stepTraces().getFirst().routeKind());
        assertEquals(FrontendBindingKind.PROPERTY, result.stepTraces().getFirst().suggestedMember().bindingKind());
        assertEquals("String", result.stepTraces().getFirst().suggestedMember().resultType().getTypeName());

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        assertEquals("int", result.stepTraces().get(1).suggestedMember().resultType().getTypeName());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.finalReceiver().status());
        assertEquals("int", result.finalReceiver().receiverType().getTypeName());
        assertNull(result.recoveryRoot());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void reducePropagatesBlockedHeadAcrossEntireSuffix() {
        var chain = chain(identifier("hero"), property("hp"), property("name"));
        var head = FrontendChainReductionHelper.ReceiverState.blockedFrom(
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Hero")),
                "chain head is blocked in current context"
        );

        var result = FrontendChainReductionHelper.reduce(request(chain, head, newRegistry(List.of(), List.of()), noExpressionTypes()));

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_BLOCKED, result.stepTraces().getFirst().routeKind());
        assertTrue(result.stepTraces().getFirst().upstreamCause().sourceStepIndex().isEmpty());

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, result.stepTraces().get(1).status());
        assertEquals(0, result.stepTraces().get(1).upstreamCause().sourceStepIndex().getAsInt());
        assertSame(chain.base(), result.recoveryRoot());
    }

    @Test
    void reduceRetriesDeferredCallArgumentsInsideFinalizeWindowAndContinuesExactSuffix() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("make", GdStringType.STRING, false, GdIntType.INT));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(worker));
        var seed = identifier("seed");
        var chain = chain(identifier("worker"), call("make", seed), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                (expression, finalizeWindow) -> expression == seed
                        ? (finalizeWindow
                        ? FrontendChainReductionHelper.ExpressionTypeResult.resolved(GdIntType.INT)
                        : FrontendChainReductionHelper.ExpressionTypeResult.deferred("seed type pending"))
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertTrue(result.stepTraces().getFirst().finalizeRetryUsed());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, result.stepTraces().getFirst().suggestedCall().callKind());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        assertNull(result.recoveryRoot());
    }

    @Test
    void reducePublishesDeferredStepAfterFinalizeWindowMissAndStopsSuffix() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("make", GdStringType.STRING, false, GdIntType.INT));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(worker));
        var seed = identifier("seed");
        var chain = chain(identifier("worker"), call("make", seed), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                (expression, finalizeWindow) -> expression == seed
                        ? FrontendChainReductionHelper.ExpressionTypeResult.deferred("seed type pending")
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.DEFERRED, result.stepTraces().getFirst().status());
        assertTrue(result.stepTraces().getFirst().finalizeRetryUsed());
        assertEquals(FrontendChainReductionHelper.Status.DEFERRED, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_DEFERRED, result.stepTraces().get(1).routeKind());
        assertEquals(0, result.stepTraces().get(1).upstreamCause().sourceStepIndex().getAsInt());
        assertSame(chain.steps().getFirst(), result.recoveryRoot());
    }

    @Test
    void reducePublishesDynamicCallAndStopsSuffix() {
        var chain = chain(identifier("worker"), call("ping"), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("MissingWorker")),
                newRegistry(List.of(), List.of()),
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.DYNAMIC, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.INSTANCE_METHOD, result.stepTraces().getFirst().routeKind());
        assertEquals(FrontendChainReductionHelper.Status.DYNAMIC, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_DYNAMIC, result.stepTraces().get(1).routeKind());
    }

    @Test
    void reducePublishesFailedMemberAndStopsSuffix() {
        var worker = newClass("Worker");
        var registry = newRegistry(List.of(), List.of(worker));
        var chain = chain(identifier("worker"), property("missing"), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.FAILED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.Status.FAILED, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_FAILED, result.stepTraces().get(1).routeKind());
        assertSame(chain.steps().getFirst(), result.recoveryRoot());
    }

    @Test
    void reduceSealsUnsupportedHeadAcrossEntireSuffix() {
        var chain = chain(identifier("ClassName"), property("VALUE"), property("next"));
        var head = FrontendChainReductionHelper.ReceiverState.unsupportedFrom(
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(typeMeta("ClassName")),
                "scope-local type alias is unsupported in MVP"
        );

        var result = FrontendChainReductionHelper.reduce(request(chain, head, newRegistry(List.of(), List.of()), noExpressionTypes()));

        assertEquals(FrontendChainReductionHelper.Status.UNSUPPORTED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_UNSUPPORTED, result.stepTraces().getFirst().routeKind());
        assertEquals(FrontendChainReductionHelper.Status.UNSUPPORTED, result.stepTraces().get(1).status());
        assertSame(chain.base(), result.recoveryRoot());
    }

    @Test
    void reduceEmitsNoteWhenInstanceSyntaxResolvesToStaticMethod() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("build", GdStringType.STRING, true, GdIntType.INT));
        var registry = newRegistry(List.of(), List.of(worker));
        var literal = literal("1");
        var chain = chain(identifier("worker"), call("build", literal));
        var noteSinkCalls = new ArrayList<FrontendChainReductionHelper.ReductionNote>();

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                (expression, finalizeWindow) -> expression == literal
                        ? FrontendChainReductionHelper.ExpressionTypeResult.resolved(GdIntType.INT)
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression"),
                noteSinkCalls::add
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.STATIC_METHOD, result.stepTraces().getFirst().routeKind());
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, result.stepTraces().getFirst().suggestedCall().callKind());
        assertEquals(1, result.notes().size());
        assertEquals(result.notes(), noteSinkCalls);
        assertTrue(result.notes().getFirst().message().contains("static method"));
    }

    @Test
    void reduceResolvesTypeMetaStaticMethodRoute() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("build", GdStringType.STRING, true, GdIntType.INT));
        var registry = newRegistry(List.of(), List.of(worker));
        var workerTypeMeta = assertInstanceOf(ScopeTypeMeta.class, registry.resolveTypeMeta("Worker"));
        var literal = literal("1");
        var chain = chain(identifier("Worker"), call("build", literal));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(workerTypeMeta),
                registry,
                (expression, finalizeWindow) -> expression == literal
                        ? FrontendChainReductionHelper.ExpressionTypeResult.resolved(GdIntType.INT)
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.STATIC_METHOD, result.stepTraces().getFirst().routeKind());
        assertEquals(FrontendReceiverKind.TYPE_META, result.stepTraces().getFirst().suggestedCall().receiverKind());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void reduceIsStableForRepeatedRuns() {
        var worker = newClass("Worker");
        worker.addProperty(new LirPropertyDef("payload", GdStringType.STRING));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(worker));
        var chain = chain(identifier("worker"), property("payload"), property("length"));
        var request = request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                noExpressionTypes()
        );

        var first = FrontendChainReductionHelper.reduce(request);
        var second = FrontendChainReductionHelper.reduce(request);

        assertEquals(first, second);
    }

    private static @NotNull FrontendChainReductionHelper.ReductionRequest request(
            @NotNull AttributeExpression chain,
            @NotNull FrontendChainReductionHelper.ReceiverState head,
            @NotNull ClassRegistry registry,
            @NotNull FrontendChainReductionHelper.ExpressionTypeResolver expressionTypeResolver
    ) {
        return request(chain, head, registry, expressionTypeResolver, _ -> {
        });
    }

    private static @NotNull FrontendChainReductionHelper.ReductionRequest request(
            @NotNull AttributeExpression chain,
            @NotNull FrontendChainReductionHelper.ReceiverState head,
            @NotNull ClassRegistry registry,
            @NotNull FrontendChainReductionHelper.ExpressionTypeResolver expressionTypeResolver,
            @NotNull FrontendChainReductionHelper.NoteSink noteSink
    ) {
        return new FrontendChainReductionHelper.ReductionRequest(
                chain,
                head,
                FrontendAnalysisData.bootstrap(),
                registry,
                expressionTypeResolver,
                noteSink
        );
    }

    private static @NotNull FrontendChainReductionHelper.ExpressionTypeResolver noExpressionTypes() {
        return (expression, finalizeWindow) -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                "unexpected expression type lookup for " + expression.getClass().getSimpleName()
        );
    }

    private static @NotNull AttributeExpression chain(@NotNull Expression base, @NotNull AttributeStep... steps) {
        return new AttributeExpression(base, List.of(steps), TINY);
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull LiteralExpression literal(@NotNull String sourceText) {
        return new LiteralExpression("number", sourceText, TINY);
    }

    private static @NotNull AttributePropertyStep property(@NotNull String name) {
        return new AttributePropertyStep(name, TINY);
    }

    private static @NotNull AttributeCallStep call(@NotNull String name, @NotNull Expression... arguments) {
        return new AttributeCallStep(name, List.of(arguments), TINY);
    }

    private static @NotNull ScopeTypeMeta typeMeta(@NotNull String name) {
        return new ScopeTypeMeta(name, name, new GdObjectType(name), ScopeTypeMetaKind.GDCC_CLASS, null, false);
    }

    private static @NotNull ClassRegistry newRegistry(
            @NotNull List<ExtensionBuiltinClass> builtinClasses,
            @NotNull List<LirClassDef> gdccClasses
    ) {
        var registry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                builtinClasses,
                List.of(),
                List.of(),
                List.of()
        ));
        for (var gdccClass : gdccClasses) {
            registry.addGdccClass(gdccClass);
        }
        return registry;
    }

    private static @NotNull LirClassDef newClass(@NotNull String name) {
        return new LirClassDef(name, "", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    /// GDCC method fixtures must follow the shared resolver's `self`-first contract for instance
    /// methods, while static methods intentionally skip that synthetic receiver parameter.
    private static @NotNull LirFunctionDef newMethod(
            @NotNull String name,
            @NotNull GdType returnType,
            boolean isStatic,
            @NotNull GdType... parameterTypes
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        function.setStatic(isStatic);

        var entryBlock = new LirBasicBlock("entry");
        function.addBasicBlock(entryBlock);
        function.setEntryBlockId("entry");

        if (!isStatic) {
            function.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, function));
        }
        for (var index = 0; index < parameterTypes.length; index++) {
            function.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, function));
        }
        return function;
    }

    private static @NotNull ExtensionBuiltinClass stringBuiltinWithLength() {
        return new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("length", "int", true, false, "0")),
                List.of()
        );
    }
}
