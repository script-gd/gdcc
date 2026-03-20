package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
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
import static org.junit.jupiter.api.Assertions.assertNull;

class FrontendChainStatusBridgeTest {
    private static final @NotNull Range TINY = new Range(0, 0, new Point(0, 0), new Point(0, 0));

    @Test
    void toPublishedExpressionTypeShouldKeepExactVariantDistinctFromDynamicFallback() {
        var exactVariant = FrontendChainStatusBridge.toPublishedExpressionType(FrontendResolvedMember.resolved(
                "payload",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                null,
                new GdObjectType("Worker"),
                GdVariantType.VARIANT,
                null
        ));
        var dynamicVariant = FrontendChainStatusBridge.toPublishedExpressionType(FrontendResolvedCall.dynamic(
                "ping",
                FrontendReceiverKind.DYNAMIC,
                null,
                GdVariantType.VARIANT,
                List.of(GdIntType.INT),
                null,
                "runtime-dynamic fallback"
        ));

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, exactVariant.status());
        assertEquals(GdVariantType.VARIANT, exactVariant.publishedType());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicVariant.status());
        assertEquals(GdVariantType.VARIANT, dynamicVariant.publishedType());
    }

    @Test
    void toExpressionTypeResultShouldDropWinnerTypeForUpstreamBlockedSuffix() throws Exception {
        var chain = chain(identifier("hero"), property("hp"), property("name"));
        var reduction = FrontendChainReductionHelper.reduce(new FrontendChainReductionHelper.ReductionRequest(
                chain,
                FrontendChainReductionHelper.ReceiverState.blockedFrom(
                        FrontendChainReductionHelper.ReceiverState.resolvedInstance(new GdObjectType("Hero")),
                        "chain head is blocked in current context"
                ),
                dev.superice.gdcc.frontend.sema.FrontendAnalysisData.bootstrap(),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                null,
                noExpressionTypes(),
                _ -> {
                }
        ));

        var reducedType = FrontendChainStatusBridge.toExpressionTypeResult(reduction);
        var publishedType = FrontendChainStatusBridge.toPublishedExpressionType(reduction);

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, reducedType.status());
        assertNull(reducedType.type());
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, publishedType.status());
        assertNull(publishedType.publishedType());
    }

    @Test
    void toReceiverStateShouldPreserveBlockedWinnerAndDynamicVariant() {
        var blockedReceiver = FrontendChainStatusBridge.toReceiverState(
                FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                        GdIntType.INT,
                        "winner exists but current context blocks it"
                )
        );
        var dynamicReceiver = FrontendChainStatusBridge.toReceiverState(
                FrontendChainReductionHelper.ExpressionTypeResult.dynamic("runtime-dynamic fallback")
        );

        assertEquals(FrontendChainReductionHelper.Status.BLOCKED, blockedReceiver.status());
        assertEquals(FrontendReceiverKind.INSTANCE, blockedReceiver.receiverKind());
        assertEquals(GdIntType.INT, blockedReceiver.receiverType());
        assertEquals(FrontendChainReductionHelper.Status.DYNAMIC, dynamicReceiver.status());
        assertEquals(FrontendReceiverKind.DYNAMIC, dynamicReceiver.receiverKind());
        assertEquals(GdVariantType.VARIANT, dynamicReceiver.receiverType());
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

    private static @NotNull AttributePropertyStep property(@NotNull String name) {
        return new AttributePropertyStep(name, TINY);
    }
}
