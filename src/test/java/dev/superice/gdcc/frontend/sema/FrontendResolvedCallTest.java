package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendResolvedCallTest {
    @Test
    void resolvedFactoryDefensivelyCopiesArgumentTypes() {
        var argumentTypes = new ArrayList<GdType>();
        argumentTypes.add(GdIntType.INT);

        var result = FrontendResolvedCall.resolved(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                argumentTypes,
                "Player.move"
        );
        argumentTypes.clear();

        assertEquals(List.of(GdIntType.INT), result.argumentTypes());
        assertThrows(UnsupportedOperationException.class, () -> result.argumentTypes().add(GdVariantType.VARIANT));
        assertNull(result.detailReason());
    }

    @Test
    void dynamicFactoryPublishesDynamicFallbackRoute() {
        var result = FrontendResolvedCall.dynamic(
                "move",
                FrontendReceiverKind.DYNAMIC,
                null,
                GdVariantType.VARIANT,
                List.of(GdIntType.INT),
                "dynamic move route",
                "receiver metadata is unavailable"
        );

        assertEquals(FrontendCallResolutionKind.DYNAMIC_FALLBACK, result.callKind());
        assertEquals(FrontendCallResolutionStatus.DYNAMIC, result.status());
        assertEquals(FrontendReceiverKind.DYNAMIC, result.receiverKind());
        assertEquals(List.of(GdIntType.INT), result.argumentTypes());
        assertEquals("receiver metadata is unavailable", result.detailReason());
    }

    @Test
    void blockedFactoryKeepsKnownReturnTypeAndReason() {
        var result = FrontendResolvedCall.blocked(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                List.of(GdIntType.INT),
                "Player.move",
                "instance method is blocked in static context"
        );

        assertEquals(FrontendCallResolutionStatus.BLOCKED, result.status());
        assertEquals(GdIntType.INT, result.returnType());
        assertEquals("instance method is blocked in static context", result.detailReason());
    }

    @Test
    void constructorRejectsResolvedCallWithoutConcreteReturnType() {
        assertThrows(NullPointerException.class, () -> new FrontendResolvedCall(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendCallResolutionStatus.RESOLVED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                List.of(GdIntType.INT),
                null,
                "Player.move",
                null
        ));
    }

    @Test
    void constructorRejectsDynamicStatusWithNonDynamicCallKind() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedCall(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendCallResolutionStatus.DYNAMIC,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                List.of(GdIntType.INT),
                null,
                "Player.move",
                "dynamic route"
        ));

        assertEquals("DYNAMIC call results must use DYNAMIC_FALLBACK callKind", ex.getMessage());
    }

    @Test
    void constructorRejectsDynamicFallbackCallKindOutsideDynamicStatus() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedCall(
                "move",
                FrontendCallResolutionKind.DYNAMIC_FALLBACK,
                FrontendCallResolutionStatus.FAILED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                List.of(GdIntType.INT),
                null,
                "Player.move",
                "failure"
        ));

        assertEquals("DYNAMIC_FALLBACK callKind is only valid for DYNAMIC call results", ex.getMessage());
    }

    @Test
    void constructorRejectsResolvedInstanceMethodWithVariantReceiverType() {
        var ex = assertThrows(IllegalArgumentException.class, () -> FrontendResolvedCall.resolved(
                "callv",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.BUILTIN,
                GdVariantType.VARIANT,
                GdIntType.INT,
                List.of(),
                "Variant.callv"
        ));

        assertEquals(
                "RESOLVED instance method calls must not publish Variant receiverType; use DYNAMIC_FALLBACK instead",
                ex.getMessage()
        );
    }

    @Test
    void resolvedFactoryStillAllowsVariantReturnTypeWhenReceiverIsConcrete() {
        var result = FrontendResolvedCall.resolved(
                "call",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.BUILTIN,
                new GdObjectType("Callable"),
                GdVariantType.VARIANT,
                List.of(),
                "Callable.call"
        );

        assertEquals(FrontendCallResolutionStatus.RESOLVED, result.status());
        assertEquals("Callable", result.receiverType().getTypeName());
        assertEquals(GdVariantType.VARIANT, result.returnType());
    }

    @Test
    void resolvedFactoryKeepsExactCallableBoundarySeparateFromCallSiteArgumentTypes() {
        var fixedParameterTypes = new ArrayList<GdType>();
        fixedParameterTypes.add(new GdObjectType("Node"));
        fixedParameterTypes.add(GdBoolType.BOOL);
        fixedParameterTypes.add(GdIntType.INT);
        var boundary = new FrontendResolvedCall.ExactCallableBoundary(fixedParameterTypes, false);

        var result = FrontendResolvedCall.resolved(
                "add_child",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.ENGINE,
                new GdObjectType("Node"),
                GdIntType.INT,
                List.of(new GdObjectType("Node")),
                "Node.add_child",
                boundary
        );
        fixedParameterTypes.clear();

        assertEquals(List.of(new GdObjectType("Node")), result.argumentTypes());
        assertNotNull(result.exactCallableBoundary());
        assertEquals(
                List.of(new GdObjectType("Node"), GdBoolType.BOOL, GdIntType.INT),
                result.exactCallableBoundary().fixedParameterTypes()
        );
        assertFalse(result.exactCallableBoundary().isVararg());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.exactCallableBoundary().fixedParameterTypes().add(GdVariantType.VARIANT)
        );
    }

    @Test
    void constructorRejectsNullArgumentTypeElements() {
        var argumentTypes = new ArrayList<GdType>();
        argumentTypes.add(GdIntType.INT);
        argumentTypes.add(null);

        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedCall(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendCallResolutionStatus.DEFERRED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                argumentTypes,
                null,
                "Player.move",
                "argument typing not ready"
        ));

        assertEquals("argumentTypes must not contain null elements", ex.getMessage());
    }

    @Test
    void constructorRejectsExactCallableBoundaryOutsideResolvedOrBlockedStatuses() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedCall(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendCallResolutionStatus.FAILED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                List.of(GdIntType.INT),
                new FrontendResolvedCall.ExactCallableBoundary(List.of(GdIntType.INT), false),
                "Player.move",
                "failure"
        ));

        assertEquals("exactCallableBoundary is only valid for RESOLVED or BLOCKED call results", ex.getMessage());
    }

    @Test
    void constructorRejectsExactCallableBoundaryForConstructorRoutes() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedCall(
                "new",
                FrontendCallResolutionKind.CONSTRUCTOR,
                FrontendCallResolutionStatus.RESOLVED,
                FrontendReceiverKind.TYPE_META,
                ScopeOwnerKind.ENGINE,
                new GdObjectType("Node"),
                new GdObjectType("Node"),
                List.of(),
                new FrontendResolvedCall.ExactCallableBoundary(List.of(new GdObjectType("Node")), false),
                "Node.new",
                null
        ));

        assertEquals("exactCallableBoundary is only valid for exact instance/static method routes", ex.getMessage());
    }

    @Test
    void constructorRejectsBlankDetailReasonForFailedCall() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedCall(
                "move",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendCallResolutionStatus.FAILED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                List.of(GdIntType.INT),
                null,
                "Player.move",
                "  "
        ));

        assertTrue(ex.getMessage().contains("detailReason must not be blank"));
    }
}
