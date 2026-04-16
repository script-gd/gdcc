package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCallMutabilitySupportTest {
    @Test
    void mayMutateReceiverTrustsGdextensionConstnessAndStaysConservativeOtherwise() {
        var receiverType = new GdObjectType("Receiver");
        var builtinConstCall = resolvedInstanceCall(
                ScopeOwnerKind.BUILTIN,
                new ExtensionBuiltinClass.ClassMethod(
                        "length",
                        "int",
                        false,
                        true,
                        false,
                        false,
                        0L,
                        List.of(),
                        List.of(),
                        new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
                ),
                receiverType
        );
        var builtinMutatingCall = resolvedInstanceCall(
                ScopeOwnerKind.BUILTIN,
                new ExtensionBuiltinClass.ClassMethod(
                        "push_back",
                        "Nil",
                        false,
                        false,
                        false,
                        false,
                        0L,
                        List.of(),
                        List.of(),
                        new ExtensionBuiltinClass.ClassMethod.ReturnValue("Nil")
                ),
                receiverType
        );
        var engineConstCall = resolvedInstanceCall(
                ScopeOwnerKind.ENGINE,
                new ExtensionGdClass.ClassMethod(
                        "get_name",
                        true,
                        false,
                        false,
                        false,
                        0L,
                        List.of(),
                        new ExtensionGdClass.ClassMethod.ClassMethodReturn("String"),
                        List.of()
                ),
                receiverType
        );
        var engineMutatingCall = resolvedInstanceCall(
                ScopeOwnerKind.ENGINE,
                new ExtensionGdClass.ClassMethod(
                        "add_child",
                        false,
                        false,
                        false,
                        false,
                        0L,
                        List.of(),
                        new ExtensionGdClass.ClassMethod.ClassMethodReturn("Nil"),
                        List.of()
                ),
                receiverType
        );
        var gdccCall = resolvedInstanceCall(ScopeOwnerKind.GDCC, new LirFunctionDef("ping"), receiverType);
        var dynamicCall = FrontendResolvedCall.dynamic(
                "ping",
                FrontendReceiverKind.INSTANCE,
                null,
                receiverType,
                List.of(),
                null,
                "runtime-open route"
        );
        var staticCall = FrontendResolvedCall.resolved(
                "make",
                FrontendCallResolutionKind.STATIC_METHOD,
                FrontendReceiverKind.TYPE_META,
                ScopeOwnerKind.BUILTIN,
                receiverType,
                GdIntType.INT,
                List.of(),
                builtinMutatingCall.declarationSite()
        );

        assertAll(
                () -> assertFalse(FrontendCallMutabilitySupport.mayMutateReceiver(builtinConstCall)),
                () -> assertTrue(FrontendCallMutabilitySupport.mayMutateReceiver(builtinMutatingCall)),
                () -> assertFalse(FrontendCallMutabilitySupport.mayMutateReceiver(engineConstCall)),
                () -> assertTrue(FrontendCallMutabilitySupport.mayMutateReceiver(engineMutatingCall)),
                () -> assertTrue(FrontendCallMutabilitySupport.mayMutateReceiver(gdccCall)),
                () -> assertTrue(FrontendCallMutabilitySupport.mayMutateReceiver(dynamicCall)),
                () -> assertFalse(FrontendCallMutabilitySupport.mayMutateReceiver(staticCall))
        );
    }

    private static FrontendResolvedCall resolvedInstanceCall(
            ScopeOwnerKind ownerKind,
            Object declarationSite,
            GdObjectType receiverType
    ) {
        return new FrontendResolvedCall(
                "ping",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendCallResolutionStatus.RESOLVED,
                FrontendReceiverKind.INSTANCE,
                ownerKind,
                receiverType,
                GdIntType.INT,
                List.of(),
                null,
                declarationSite,
                null
        );
    }
}
