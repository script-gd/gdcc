package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionEnumValue;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.gdextension.ExtensionGlobalEnum;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        var payloadMember = result.stepTraces().getFirst().suggestedMember();
        assertNotNull(payloadMember);
        assertEquals(FrontendBindingKind.PROPERTY, payloadMember.bindingKind());
        var payloadType = payloadMember.resultType();
        assertNotNull(payloadType);
        assertEquals("String", payloadType.getTypeName());

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        var lengthMember = result.stepTraces().get(1).suggestedMember();
        assertNotNull(lengthMember);
        var lengthType = lengthMember.resultType();
        assertNotNull(lengthType);
        assertEquals("int", lengthType.getTypeName());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.finalReceiver().status());
        var finalReceiverType = result.finalReceiver().receiverType();
        assertNotNull(finalReceiverType);
        assertEquals("int", finalReceiverType.getTypeName());
        assertNull(result.recoveryRoot());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void reducePropagatesBlockedHeadAcrossEntireSuffix() {
        var hero = newClass("Hero");
        hero.addProperty(new LirPropertyDef("hp", GdStringType.STRING));
        var chain = chain(identifier("hero"), property("hp"), property("length"));
        var head = FrontendChainReductionHelper.ReceiverState.blockedFrom(
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Hero")),
                "chain head is blocked in current context"
        );

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                head,
                newRegistry(List.of(stringBuiltinWithLength()), List.of(hero)),
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.INSTANCE_PROPERTY, result.stepTraces().getFirst().routeKind());
        var blockedMember = result.stepTraces().getFirst().suggestedMember();
        assertNotNull(blockedMember);
        assertEquals(FrontendBindingKind.PROPERTY, blockedMember.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, blockedMember.receiverKind());
        var blockedMemberType = blockedMember.resultType();
        assertNotNull(blockedMemberType);
        assertEquals("String", blockedMemberType.getTypeName());
        var firstUpstreamCause = result.stepTraces().getFirst().upstreamCause();
        assertNotNull(firstUpstreamCause);
        assertTrue(firstUpstreamCause.sourceStepIndex().isEmpty());

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_BLOCKED, result.stepTraces().get(1).routeKind());
        var secondUpstreamCause = result.stepTraces().get(1).upstreamCause();
        assertNotNull(secondUpstreamCause);
        assertTrue(secondUpstreamCause.sourceStepIndex().isPresent());
        assertEquals(0, secondUpstreamCause.sourceStepIndex().getAsInt());
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
        var makeCall = result.stepTraces().getFirst().suggestedCall();
        assertNotNull(makeCall);
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, makeCall.callKind());
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
        var deferredUpstreamCause = result.stepTraces().get(1).upstreamCause();
        assertNotNull(deferredUpstreamCause);
        assertTrue(deferredUpstreamCause.sourceStepIndex().isPresent());
        assertEquals(0, deferredUpstreamCause.sourceStepIndex().getAsInt());
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
    void reduceUsesDynamicArgumentVariantToContinueExactCallResolution() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("consume", GdStringType.STRING, false, GdVariantType.VARIANT));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(worker));
        var seed = identifier("seed");
        var chain = chain(identifier("worker"), call("consume", seed), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                (expression, finalizeWindow) -> expression == seed
                        ? FrontendChainReductionHelper.ExpressionTypeResult.dynamic("seed routes through runtime-dynamic semantics")
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        var consumeCall = result.stepTraces().getFirst().suggestedCall();
        assertNotNull(consumeCall);
        assertEquals(List.of(GdVariantType.VARIANT), consumeCall.argumentTypes());
    }

    @Test
    void reducePreservesBlockedArgumentDependencyInsteadOfThrowing() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("consume", GdStringType.STRING, false, GdVariantType.VARIANT));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(worker));
        var seed = identifier("seed");
        var chain = chain(identifier("worker"), call("consume", seed), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                (expression, finalizeWindow) -> expression == seed
                        ? FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                        null,
                        "seed is blocked by upstream restriction"
                )
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, result.stepTraces().getFirst().status());
        assertNull(result.stepTraces().getFirst().suggestedCall());
        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_BLOCKED, result.stepTraces().get(1).routeKind());
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
    void reducePublishesConcreteFirstStepForHeadFailureThenPropagatesSuffix() {
        var chain = chain(identifier("ghost"), property("payload"), property("length"));
        var headFailure = new FrontendChainReductionHelper.ReceiverState(
                FrontendChainReductionHelper.Status.FAILED,
                FrontendReceiverKind.UNKNOWN,
                null,
                null,
                "No published chain-head binding fact is available for identifier 'ghost'"
        );

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                headFailure,
                newRegistry(List.of(), List.of()),
                noExpressionTypes()
        ));

        var firstTrace = result.stepTraces().getFirst();
        assertEquals(FrontendChainReductionHelper.Status.FAILED, firstTrace.status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_FAILED, firstTrace.routeKind());
        assertNotNull(firstTrace.suggestedMember());
        assertEquals("payload", firstTrace.suggestedMember().memberName());
        assertEquals(FrontendReceiverKind.UNKNOWN, firstTrace.suggestedMember().receiverKind());

        var secondTrace = result.stepTraces().get(1);
        assertEquals(FrontendChainReductionHelper.Status.FAILED, secondTrace.status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_FAILED, secondTrace.routeKind());
        assertNull(secondTrace.suggestedMember());

        assertSame(chain.base(), result.recoveryRoot());
        assertEquals(FrontendChainReductionHelper.Status.FAILED, result.finalReceiver().status());
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
        var buildCall = result.stepTraces().getFirst().suggestedCall();
        assertNotNull(buildCall);
        assertEquals(FrontendCallResolutionKind.STATIC_METHOD, buildCall.callKind());
        assertEquals(1, result.notes().size());
        assertEquals(result.notes(), noteSinkCalls);
        assertTrue(result.notes().getFirst().message().contains("static method"));
    }

    @Test
    void reduceResolvesAttributeSubscriptAndContinuesExactSuffix() {
        var holder = newClass("Holder");
        holder.addProperty(new LirPropertyDef("names", new GdArrayType(GdStringType.STRING)));
        var registry = newRegistry(List.of(stringBuiltinWithLength()), List.of(holder));
        var index = literal("0");
        var chain = chain(identifier("holder"), subscript("names", index), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Holder")),
                registry,
                (expression, finalizeWindow) -> expression == index
                        ? FrontendChainReductionHelper.ExpressionTypeResult.resolved(GdIntType.INT)
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.SUBSCRIPT, result.stepTraces().getFirst().routeKind());
        assertNull(result.stepTraces().getFirst().suggestedMember());
        assertNull(result.stepTraces().getFirst().suggestedCall());
        var subscriptReceiverType = result.stepTraces().getFirst().outgoingReceiver().receiverType();
        assertNotNull(subscriptReceiverType);
        assertEquals("String", subscriptReceiverType.getTypeName());

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        var lengthMember = result.stepTraces().get(1).suggestedMember();
        assertNotNull(lengthMember);
        var lengthType = lengthMember.resultType();
        assertNotNull(lengthType);
        assertEquals("int", lengthType.getTypeName());
        assertNull(result.recoveryRoot());
    }

    @Test
    void reducePublishesUnsupportedAttributeSubscriptWhenMemberTypeIsOnlyKeyedMetadata() {
        var holder = newClass("Holder");
        holder.addProperty(new LirPropertyDef("text", GdStringType.STRING));
        var registry = newRegistry(List.of(keyedStringBuiltin()), List.of(holder));
        var index = literal("0");
        var chain = chain(identifier("holder"), subscript("text", index), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Holder")),
                registry,
                (expression, finalizeWindow) -> expression == index
                        ? FrontendChainReductionHelper.ExpressionTypeResult.resolved(GdIntType.INT)
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.UNSUPPORTED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.SUBSCRIPT, result.stepTraces().getFirst().routeKind());
        assertTrue(result.stepTraces().getFirst().detailReason().contains("keyed access metadata"));
        assertEquals(FrontendChainReductionHelper.Status.UNSUPPORTED, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_UNSUPPORTED, result.stepTraces().get(1).routeKind());
        assertSame(chain.steps().getFirst(), result.recoveryRoot());
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
        var staticBuildCall = result.stepTraces().getFirst().suggestedCall();
        assertNotNull(staticBuildCall);
        assertEquals(FrontendReceiverKind.TYPE_META, staticBuildCall.receiverKind());
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void reduceResolvesInstanceMethodReferenceAfterPropertyAndSignalMiss() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("speak", GdStringType.STRING, false));
        var registry = newRegistry(List.of(), List.of(worker));
        var chain = chain(identifier("worker"), property("speak"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Worker")),
                registry,
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.INSTANCE_METHOD, result.stepTraces().getFirst().routeKind());
        var methodReference = result.stepTraces().getFirst().suggestedMember();
        assertNotNull(methodReference);
        assertEquals(FrontendBindingKind.METHOD, methodReference.bindingKind());
        assertEquals(FrontendReceiverKind.INSTANCE, methodReference.receiverKind());
        assertInstanceOf(GdCallableType.class, methodReference.resultType());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.finalReceiver().status());
        assertInstanceOf(GdCallableType.class, result.finalReceiver().receiverType());
    }

    @Test
    void reduceResolvesTypeMetaStaticMethodReferenceAfterStaticLoadMiss() {
        var worker = newClass("Worker");
        worker.addFunction(newMethod("build", GdStringType.STRING, true));
        var registry = newRegistry(List.of(), List.of(worker));
        var chain = chain(identifier("Worker"), property("build"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(typeMeta("Worker")),
                registry,
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.STATIC_METHOD, result.stepTraces().getFirst().routeKind());
        var methodReference = result.stepTraces().getFirst().suggestedMember();
        assertNotNull(methodReference);
        assertEquals(FrontendBindingKind.STATIC_METHOD, methodReference.bindingKind());
        assertEquals(FrontendReceiverKind.TYPE_META, methodReference.receiverKind());
        assertInstanceOf(GdCallableType.class, methodReference.resultType());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.finalReceiver().status());
        assertInstanceOf(GdCallableType.class, result.finalReceiver().receiverType());
    }

    @Test
    void reducePublishesDynamicMemberWhenObjectReceiverMetadataIsUnknown() {
        var chain = chain(identifier("worker"), property("hp"), property("length"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("MissingWorker")),
                newRegistry(List.of(), List.of()),
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.DYNAMIC, result.stepTraces().getFirst().status());
        assertEquals(FrontendChainReductionHelper.RouteKind.INSTANCE_PROPERTY, result.stepTraces().getFirst().routeKind());
        assertEquals(FrontendChainReductionHelper.Status.DYNAMIC, result.stepTraces().get(1).status());
        assertEquals(FrontendChainReductionHelper.RouteKind.UPSTREAM_DYNAMIC, result.stepTraces().get(1).routeKind());
    }

    @Test
    void reduceResolvesBuiltinConstructorAndContinuesWithExactSuffix() {
        var registry = newRegistry(List.of(stringBuiltinWithLengthAndIntConstructor()), List.of());
        var chain = chain(identifier("String"), call("new", literal("1")), property("length"));
        var constructorStep = assertInstanceOf(AttributeCallStep.class, chain.steps().getFirst());
        var constructorArgument = constructorStep.arguments().getFirst();

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(
                        typeMeta("String", GdStringType.STRING, ScopeTypeMetaKind.BUILTIN, null, false)
                ),
                registry,
                (expression, finalizeWindow) -> expression == constructorArgument
                        ? FrontendChainReductionHelper.ExpressionTypeResult.resolved(GdIntType.INT)
                        : FrontendChainReductionHelper.ExpressionTypeResult.failed("unexpected expression")
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        var constructorCall = result.stepTraces().getFirst().suggestedCall();
        assertNotNull(constructorCall);
        assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, constructorCall.callKind());
        var constructorReturnType = constructorCall.returnType();
        assertNotNull(constructorReturnType);
        assertEquals("String", constructorReturnType.getTypeName());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        var builtinLengthMember = result.stepTraces().get(1).suggestedMember();
        assertNotNull(builtinLengthMember);
        var builtinLengthType = builtinLengthMember.resultType();
        assertNotNull(builtinLengthType);
        assertEquals("int", builtinLengthType.getTypeName());
    }

    @Test
    void reduceResolvesGdccDefaultConstructorWithoutExplicitInitFunction() {
        var worker = newClass("Worker");
        worker.addProperty(new LirPropertyDef("payload", GdStringType.STRING));
        var registry = newRegistry(List.of(), List.of(worker));
        var chain = chain(identifier("Worker"), call("new"), property("payload"));

        var result = FrontendChainReductionHelper.reduce(request(
                chain,
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(typeMeta("Worker")),
                registry,
                noExpressionTypes()
        ));

        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().getFirst().status());
        var defaultConstructorCall = result.stepTraces().getFirst().suggestedCall();
        assertNotNull(defaultConstructorCall);
        assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, defaultConstructorCall.callKind());
        var defaultConstructorReturnType = defaultConstructorCall.returnType();
        assertNotNull(defaultConstructorReturnType);
        assertEquals("Worker", defaultConstructorReturnType.getTypeName());
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, result.stepTraces().get(1).status());
        var payloadMember = result.stepTraces().get(1).suggestedMember();
        assertNotNull(payloadMember);
        var payloadResultType = payloadMember.resultType();
        assertNotNull(payloadResultType);
        assertEquals("String", payloadResultType.getTypeName());
    }

    @Test
    void reduceResolvesGlobalEnumBuiltinAndEngineStaticLoads() {
        var globalEnum = new ExtensionGlobalEnum("Side", false, List.of(new ExtensionEnumValue("SIDE_LEFT", 0)));
        var engineClass = new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass.ConstantInfo("NOTIFICATION_ENTER_TREE", "10"))
        );
        var registry = newRegistry(
                List.of(vector3BuiltinWithBackConstant()),
                List.of(engineClass),
                List.of(globalEnum),
                List.of()
        );

        var enumResult = FrontendChainReductionHelper.reduce(request(
                chain(identifier("Side"), property("SIDE_LEFT")),
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(
                        typeMeta("Side", GdIntType.INT, ScopeTypeMetaKind.GLOBAL_ENUM, globalEnum, true)
                ),
                registry,
                noExpressionTypes()
        ));
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, enumResult.stepTraces().getFirst().status());
        var enumMember = enumResult.stepTraces().getFirst().suggestedMember();
        assertNotNull(enumMember);
        var enumResultType = enumMember.resultType();
        assertNotNull(enumResultType);
        assertEquals("int", enumResultType.getTypeName());

        var builtinResult = FrontendChainReductionHelper.reduce(request(
                chain(identifier("Vector3"), property("BACK")),
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(
                        typeMeta("Vector3", GdFloatVectorType.VECTOR3, ScopeTypeMetaKind.BUILTIN, null, false)
                ),
                registry,
                noExpressionTypes()
        ));
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, builtinResult.stepTraces().getFirst().status());
        var builtinMember = builtinResult.stepTraces().getFirst().suggestedMember();
        assertNotNull(builtinMember);
        var builtinResultType = builtinMember.resultType();
        assertNotNull(builtinResultType);
        assertEquals("Vector3", builtinResultType.getTypeName());

        var engineResult = FrontendChainReductionHelper.reduce(request(
                chain(identifier("Node"), property("NOTIFICATION_ENTER_TREE")),
                FrontendChainReductionHelper.ReceiverState.resolvedTypeMeta(
                        typeMeta("Node", new GdObjectType("Node"), ScopeTypeMetaKind.ENGINE_CLASS, engineClass, false)
                ),
                registry,
                noExpressionTypes()
        ));
        assertEquals(FrontendChainReductionHelper.Status.RESOLVED, engineResult.stepTraces().getFirst().status());
        var engineMember = engineResult.stepTraces().getFirst().suggestedMember();
        assertNotNull(engineMember);
        var engineResultType = engineMember.resultType();
        assertNotNull(engineResultType);
        assertEquals("int", engineResultType.getTypeName());
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
                null,
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

    private static @NotNull AttributeSubscriptStep subscript(@NotNull String name, @NotNull Expression... arguments) {
        return new AttributeSubscriptStep(name, List.of(arguments), TINY);
    }

    private static @NotNull ScopeTypeMeta typeMeta(@NotNull String name) {
        return typeMeta(name, new GdObjectType(name), ScopeTypeMetaKind.GDCC_CLASS, null, false);
    }

    private static @NotNull ScopeTypeMeta typeMeta(
            @NotNull String sourceName,
            @NotNull GdType instanceType,
            @NotNull ScopeTypeMetaKind kind,
            @Nullable Object declaration,
            boolean pseudoType
    ) {
        return new ScopeTypeMeta(sourceName, sourceName, instanceType, kind, declaration, pseudoType);
    }

    private static @NotNull ClassRegistry newRegistry(
            @NotNull List<ExtensionBuiltinClass> builtinClasses,
            @NotNull List<LirClassDef> gdccClasses
    ) {
        return newRegistry(builtinClasses, List.of(), List.of(), gdccClasses);
    }

    private static @NotNull ClassRegistry newRegistry(
            @NotNull List<ExtensionBuiltinClass> builtinClasses,
            @NotNull List<ExtensionGdClass> engineClasses,
            @NotNull List<ExtensionGlobalEnum> globalEnums,
            @NotNull List<LirClassDef> gdccClasses
    ) {
        var registry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                globalEnums,
                List.of(),
                builtinClasses,
                engineClasses,
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

    private static @NotNull ExtensionBuiltinClass stringBuiltinWithLengthAndIntConstructor() {
        return new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.ConstructorInfo(
                        "String",
                        0,
                        List.of(new ExtensionFunctionArgument("value", "int", null, null))
                )),
                List.of(new ExtensionBuiltinClass.PropertyInfo("length", "int", true, false, "0")),
                List.of()
        );
    }

    private static @NotNull ExtensionBuiltinClass keyedStringBuiltin() {
        return new ExtensionBuiltinClass(
                "String",
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.PropertyInfo("length", "int", true, false, "0")),
                List.of()
        );
    }

    private static @NotNull ExtensionBuiltinClass vector3BuiltinWithBackConstant() {
        return new ExtensionBuiltinClass(
                "Vector3",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionBuiltinClass.ConstantInfo("BACK", "Vector3", "Vector3(0, 0, -1)"))
        );
    }
}
