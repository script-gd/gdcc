package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrontendResolvedMemberTest {
    @Test
    void resolvedFactoryPublishesConcreteMemberHit() {
        var receiverType = new GdObjectType("Player");
        var result = FrontendResolvedMember.resolved(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                receiverType,
                GdIntType.INT,
                "Player.hp"
        );

        assertEquals("hp", result.memberName());
        assertEquals(FrontendBindingKind.PROPERTY, result.bindingKind());
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, result.status());
        assertEquals(FrontendReceiverKind.INSTANCE, result.receiverKind());
        assertEquals(ScopeOwnerKind.GDCC, result.ownerKind());
        assertEquals(receiverType, result.receiverType());
        assertEquals(GdIntType.INT, result.resultType());
        assertEquals("Player.hp", result.declarationSite());
        assertNull(result.detailReason());
    }

    @Test
    void blockedFactoryKeepsConcreteResultTypeAndReason() {
        var result = FrontendResolvedMember.blocked(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                "Player.hp",
                "instance member is blocked in static context"
        );

        assertEquals(FrontendMemberResolutionStatus.BLOCKED, result.status());
        assertEquals(GdIntType.INT, result.resultType());
        assertEquals("instance member is blocked in static context", result.detailReason());
    }

    @Test
    void dynamicFactoryPublishesRuntimeOpenMemberRouteWithoutResultType() {
        var result = FrontendResolvedMember.dynamic(
                "marker",
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.BUILTIN,
                GdVariantType.VARIANT,
                "Variant.marker",
                "runtime-dynamic property access"
        );

        assertEquals("marker", result.memberName());
        assertEquals(FrontendBindingKind.PROPERTY, result.bindingKind());
        assertEquals(FrontendMemberResolutionStatus.DYNAMIC, result.status());
        assertEquals(FrontendReceiverKind.INSTANCE, result.receiverKind());
        assertEquals(ScopeOwnerKind.BUILTIN, result.ownerKind());
        assertEquals(GdVariantType.VARIANT, result.receiverType());
        assertNull(result.resultType());
        assertEquals("Variant.marker", result.declarationSite());
        assertEquals("runtime-dynamic property access", result.detailReason());
    }

    @Test
    void constructorRejectsResolvedMemberWithoutConcreteResultType() {
        assertThrows(NullPointerException.class, () -> new FrontendResolvedMember(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendMemberResolutionStatus.RESOLVED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                null,
                "Player.hp",
                null
        ));
    }

    @Test
    void constructorRejectsBlockedMemberWithUnknownBindingKind() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedMember(
                "hp",
                FrontendBindingKind.UNKNOWN,
                FrontendMemberResolutionStatus.BLOCKED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                "Player.hp",
                "blocked"
        ));

        assertEquals("bindingKind must not be UNKNOWN for published member hits", ex.getMessage());
    }

    @Test
    void constructorRejectsDeferredMemberThatPretendsToHaveConcreteResultType() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedMember(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendMemberResolutionStatus.DEFERRED,
                FrontendReceiverKind.INSTANCE,
                ScopeOwnerKind.GDCC,
                new GdObjectType("Player"),
                GdIntType.INT,
                "Player.hp",
                "argument typing is not ready yet"
        ));

        assertEquals("resultType must be null for non-success member results", ex.getMessage());
    }

    @Test
    void constructorRejectsDynamicMemberWithUnknownReceiverKind() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedMember(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendMemberResolutionStatus.DYNAMIC,
                FrontendReceiverKind.UNKNOWN,
                null,
                null,
                null,
                null,
                "receiver metadata is unavailable"
        ));

        assertEquals("receiverKind must not be UNKNOWN for DYNAMIC member results", ex.getMessage());
    }

    @Test
    void constructorRejectsBlankDetailReasonForUnsupportedMember() {
        assertThrows(IllegalArgumentException.class, () -> new FrontendResolvedMember(
                "hp",
                FrontendBindingKind.PROPERTY,
                FrontendMemberResolutionStatus.UNSUPPORTED,
                FrontendReceiverKind.TYPE_META,
                null,
                null,
                null,
                null,
                "   "
        ));
    }
}
