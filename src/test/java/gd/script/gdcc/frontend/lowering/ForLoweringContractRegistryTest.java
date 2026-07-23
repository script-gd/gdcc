package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.backend.c.gen.intrinsic.CForRangeIterIntrinsic;
import gd.script.gdcc.frontend.sema.FrontendForIterationRoute;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Anchors the compile-time lowering contract registry to `doc/gdcc_lir_intrinsic.md`: range/int
/// routes are compile-ready with the frozen `gdcc.for_range_iter.*` signatures, while generic and
/// reserved routes stay route-not-ready (null) until their contracts are registered.
class ForLoweringContractRegistryTest {
    @Test
    void rangeCallRouteExposesFrozenRangeContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.RANGE_CALL);
        assertNotNull(contract);
        assertSame(GdccForRangeIterType.FOR_RANGE_ITER, contract.iteratorStateType());
        assertRangeOperationSignatures(contract);
    }

    @Test
    void intShorthandRouteSharesTheRangeContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.INT_SHORTHAND);
        assertNotNull(contract);
        assertSame(GdccForRangeIterType.FOR_RANGE_ITER, contract.iteratorStateType());
        assertRangeOperationSignatures(contract);
    }

    @Test
    void genericVariantAndReservedRoutesAreNotCompileReady() {
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.GENERIC_VARIANT));
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.FLOAT_SHORTHAND));
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.STRING));
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.ARRAY));
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.DICTIONARY_KEYS));
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.PACKED_ARRAY));
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.OBJECT_CUSTOM));
    }

    @Test
    void rangeIntrinsicNamesStayAlignedWithBackendContract() {
        // The registry holds the frontend copy of the frozen intrinsic names; the C backend recognizes
        // only its matching copy. Any drift would make lowered LIR unrecognizable to the backend, so
        // this cross-module assertion locks the two copies together.
        assertEquals(CForRangeIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.RANGE_INIT_INTRINSIC);
        assertEquals(
                CForRangeIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.RANGE_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForRangeIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.RANGE_NEXT_INTRINSIC);
        assertEquals(CForRangeIterIntrinsic.GET_NAME, ForLoweringContractRegistry.RANGE_GET_INTRINSIC);
    }

    private static void assertRangeOperationSignatures(FrontendForLoweringContract contract) {
        var state = GdccForRangeIterType.FOR_RANGE_ITER;

        assertEquals(ForLoweringContractRegistry.RANGE_INIT_INTRINSIC, contract.init().intrinsicName());
        assertSame(state, contract.init().resultType());
        assertEquals(List.of(GdIntType.INT, GdIntType.INT, GdIntType.INT), contract.init().argumentTypes());

        assertEquals(
                ForLoweringContractRegistry.RANGE_SHOULD_CONTINUE_INTRINSIC,
                contract.shouldContinue().intrinsicName()
        );
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(state), contract.shouldContinue().argumentTypes());

        assertEquals(ForLoweringContractRegistry.RANGE_NEXT_INTRINSIC, contract.next().intrinsicName());
        assertSame(state, contract.next().resultType());
        assertEquals(List.of(state), contract.next().argumentTypes());

        assertEquals(ForLoweringContractRegistry.RANGE_GET_INTRINSIC, contract.get().intrinsicName());
        assertSame(GdIntType.INT, contract.get().resultType());
        assertEquals(List.of(state), contract.get().argumentTypes());
    }
}
