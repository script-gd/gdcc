package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForArrayIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForDictionaryIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForFloatIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.packed.CForPackedArrayIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForRangeIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForStringIterIntrinsic;
import gd.script.gdcc.backend.c.gen.intrinsic.foriter.CForVariantIterIntrinsic;
import gd.script.gdcc.frontend.sema.FrontendForIterationRoute;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForArrayIterType;
import gd.script.gdcc.type.GdccForDictionaryIterType;
import gd.script.gdcc.type.GdccForFloatIterType;
import gd.script.gdcc.type.GdccForPackedArrayIterType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdccForStringIterType;
import gd.script.gdcc.type.GdccForVariantIterType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Anchors the compile-time lowering contract registry to `doc/gdcc_lir_intrinsic.md`: range/int
/// routes are compile-ready with the frozen `gdcc.for_range_iter.*` signatures, generic Variant
/// route is compile-ready with `gdcc.for_variant_iter.*` signatures, and remaining reserved routes
/// stay route-not-ready (null) until their contracts are registered.
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
    void genericVariantRouteExposesFrozenVariantContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.GENERIC_VARIANT);
        assertNotNull(contract);
        assertSame(GdccForVariantIterType.FOR_VARIANT_ITER, contract.iteratorStateType());
        assertVariantOperationSignatures(contract);
    }

    @Test
    void stringRouteExposesFrozenStringContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.STRING);
        assertNotNull(contract);
        assertSame(GdccForStringIterType.FOR_STRING_ITER, contract.iteratorStateType());
        assertStringOperationSignatures(contract);
    }

    @Test
    void arrayRouteExposesFrozenArrayContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.ARRAY);
        assertNotNull(contract);
        assertSame(GdccForArrayIterType.FOR_ARRAY_ITER, contract.iteratorStateType());
        assertArrayOperationSignatures(contract);
    }

    @Test
    void dictionaryKeysRouteExposesFrozenDictionaryContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.DICTIONARY_KEYS);
        assertNotNull(contract);
        assertSame(GdccForDictionaryIterType.FOR_DICTIONARY_ITER, contract.iteratorStateType());
        assertDictionaryOperationSignatures(contract);
    }

    @Test
    void packedArrayRoutesExposeFrozenPerFamilyContracts() {
        for (var family : GdccForPackedArrayIterType.all()) {
            var route = ForLoweringContractRegistry.routeForPackedFamily(family);
            var contract = ForLoweringContractRegistry.get(route);
            assertNotNull(contract, () -> "missing contract for " + route);
            assertSame(family, contract.iteratorStateType());
            assertPackedArrayOperationSignatures(contract, family);
        }
    }

    @Test
    void floatShorthandRouteExposesFrozenFloatContract() {
        var contract = ForLoweringContractRegistry.get(FrontendForIterationRoute.FLOAT_SHORTHAND);
        assertNotNull(contract);
        assertSame(GdccForFloatIterType.FOR_FLOAT_ITER, contract.iteratorStateType());
        assertFloatOperationSignatures(contract);
    }

    @Test
    void reservedRoutesAreNotCompileReady() {
        assertNull(ForLoweringContractRegistry.get(FrontendForIterationRoute.OBJECT_CUSTOM));
    }

    @Test
    void rangeIntrinsicNamesStayAlignedWithBackendContract() {
        assertEquals(CForRangeIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.RANGE_INIT_INTRINSIC);
        assertEquals(
                CForRangeIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.RANGE_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForRangeIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.RANGE_NEXT_INTRINSIC);
        assertEquals(CForRangeIterIntrinsic.GET_NAME, ForLoweringContractRegistry.RANGE_GET_INTRINSIC);
    }

    @Test
    void variantIntrinsicNamesStayAlignedWithBackendContract() {
        assertEquals(CForVariantIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.VARIANT_INIT_INTRINSIC);
        assertEquals(
                CForVariantIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.VARIANT_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForVariantIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.VARIANT_NEXT_INTRINSIC);
        assertEquals(CForVariantIterIntrinsic.GET_NAME, ForLoweringContractRegistry.VARIANT_GET_INTRINSIC);
    }

    @Test
    void stringIntrinsicNamesStayAlignedWithBackendContract() {
        assertEquals(CForStringIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.STRING_INIT_INTRINSIC);
        assertEquals(
                CForStringIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.STRING_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForStringIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.STRING_NEXT_INTRINSIC);
        assertEquals(CForStringIterIntrinsic.GET_NAME, ForLoweringContractRegistry.STRING_GET_INTRINSIC);
    }

    @Test
    void arrayIntrinsicNamesStayAlignedWithBackendContract() {
        assertEquals(CForArrayIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.ARRAY_INIT_INTRINSIC);
        assertEquals(
                CForArrayIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.ARRAY_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForArrayIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.ARRAY_NEXT_INTRINSIC);
        assertEquals(CForArrayIterIntrinsic.GET_NAME, ForLoweringContractRegistry.ARRAY_GET_INTRINSIC);
    }

    @Test
    void dictionaryIntrinsicNamesStayAlignedWithBackendContract() {
        assertEquals(CForDictionaryIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.DICTIONARY_INIT_INTRINSIC);
        assertEquals(
                CForDictionaryIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.DICTIONARY_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForDictionaryIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.DICTIONARY_NEXT_INTRINSIC);
        assertEquals(CForDictionaryIterIntrinsic.GET_NAME, ForLoweringContractRegistry.DICTIONARY_GET_INTRINSIC);
    }

    @Test
    void packedArrayIntrinsicNamesStayAlignedWithBackendContract() {
        for (var family : GdccForPackedArrayIterType.all()) {
            var init = CForPackedArrayIterIntrinsic.init(family);
            var shouldContinue = CForPackedArrayIterIntrinsic.shouldContinue(family);
            var next = CForPackedArrayIterIntrinsic.next(family);
            var get = CForPackedArrayIterIntrinsic.get(family);
            assertEquals(family.getInitIntrinsicName(), init.name());
            assertEquals(family.getShouldContinueIntrinsicName(), shouldContinue.name());
            assertEquals(family.getNextIntrinsicName(), next.name());
            assertEquals(family.getGetIntrinsicName(), get.name());
        }
    }

    @Test
    void floatIntrinsicNamesStayAlignedWithBackendContract() {
        assertEquals(CForFloatIterIntrinsic.INIT_NAME, ForLoweringContractRegistry.FLOAT_INIT_INTRINSIC);
        assertEquals(
                CForFloatIterIntrinsic.SHOULD_CONTINUE_NAME,
                ForLoweringContractRegistry.FLOAT_SHOULD_CONTINUE_INTRINSIC
        );
        assertEquals(CForFloatIterIntrinsic.NEXT_NAME, ForLoweringContractRegistry.FLOAT_NEXT_INTRINSIC);
        assertEquals(CForFloatIterIntrinsic.GET_NAME, ForLoweringContractRegistry.FLOAT_GET_INTRINSIC);
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

    private static void assertVariantOperationSignatures(FrontendForLoweringContract contract) {
        var state = GdccForVariantIterType.FOR_VARIANT_ITER;

        assertEquals(ForLoweringContractRegistry.VARIANT_INIT_INTRINSIC, contract.init().intrinsicName());
        assertSame(state, contract.init().resultType());
        assertEquals(List.of(GdVariantType.VARIANT), contract.init().argumentTypes());

        assertEquals(
                ForLoweringContractRegistry.VARIANT_SHOULD_CONTINUE_INTRINSIC,
                contract.shouldContinue().intrinsicName()
        );
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(state), contract.shouldContinue().argumentTypes());

        assertEquals(ForLoweringContractRegistry.VARIANT_NEXT_INTRINSIC, contract.next().intrinsicName());
        assertSame(state, contract.next().resultType());
        assertEquals(List.of(state), contract.next().argumentTypes());

        assertEquals(ForLoweringContractRegistry.VARIANT_GET_INTRINSIC, contract.get().intrinsicName());
        assertSame(GdVariantType.VARIANT, contract.get().resultType());
        assertEquals(List.of(state), contract.get().argumentTypes());
    }

    private static void assertStringOperationSignatures(FrontendForLoweringContract contract) {
        var state = GdccForStringIterType.FOR_STRING_ITER;

        assertEquals(ForLoweringContractRegistry.STRING_INIT_INTRINSIC, contract.init().intrinsicName());
        assertSame(state, contract.init().resultType());
        assertEquals(List.of(GdStringType.STRING), contract.init().argumentTypes());

        assertEquals(
                ForLoweringContractRegistry.STRING_SHOULD_CONTINUE_INTRINSIC,
                contract.shouldContinue().intrinsicName()
        );
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(state), contract.shouldContinue().argumentTypes());

        assertEquals(ForLoweringContractRegistry.STRING_NEXT_INTRINSIC, contract.next().intrinsicName());
        assertSame(state, contract.next().resultType());
        assertEquals(List.of(state), contract.next().argumentTypes());

        assertEquals(ForLoweringContractRegistry.STRING_GET_INTRINSIC, contract.get().intrinsicName());
        assertSame(GdStringType.STRING, contract.get().resultType());
        assertEquals(List.of(state), contract.get().argumentTypes());
    }

    private static void assertArrayOperationSignatures(FrontendForLoweringContract contract) {
        var state = GdccForArrayIterType.FOR_ARRAY_ITER;

        assertEquals(ForLoweringContractRegistry.ARRAY_INIT_INTRINSIC, contract.init().intrinsicName());
        assertSame(state, contract.init().resultType());
        assertEquals(List.of(GdVariantType.VARIANT), contract.init().argumentTypes());

        assertEquals(
                ForLoweringContractRegistry.ARRAY_SHOULD_CONTINUE_INTRINSIC,
                contract.shouldContinue().intrinsicName()
        );
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(state), contract.shouldContinue().argumentTypes());

        assertEquals(ForLoweringContractRegistry.ARRAY_NEXT_INTRINSIC, contract.next().intrinsicName());
        assertSame(state, contract.next().resultType());
        assertEquals(List.of(state), contract.next().argumentTypes());

        assertEquals(ForLoweringContractRegistry.ARRAY_GET_INTRINSIC, contract.get().intrinsicName());
        assertSame(GdVariantType.VARIANT, contract.get().resultType());
        assertEquals(List.of(state), contract.get().argumentTypes());
    }

    private static void assertDictionaryOperationSignatures(FrontendForLoweringContract contract) {
        var state = GdccForDictionaryIterType.FOR_DICTIONARY_ITER;

        assertEquals(ForLoweringContractRegistry.DICTIONARY_INIT_INTRINSIC, contract.init().intrinsicName());
        assertSame(state, contract.init().resultType());
        assertEquals(List.of(GdVariantType.VARIANT), contract.init().argumentTypes());

        assertEquals(
                ForLoweringContractRegistry.DICTIONARY_SHOULD_CONTINUE_INTRINSIC,
                contract.shouldContinue().intrinsicName()
        );
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(state), contract.shouldContinue().argumentTypes());

        assertEquals(ForLoweringContractRegistry.DICTIONARY_NEXT_INTRINSIC, contract.next().intrinsicName());
        assertSame(state, contract.next().resultType());
        assertEquals(List.of(state), contract.next().argumentTypes());

        assertEquals(ForLoweringContractRegistry.DICTIONARY_GET_INTRINSIC, contract.get().intrinsicName());
        assertSame(GdVariantType.VARIANT, contract.get().resultType());
        assertEquals(List.of(state), contract.get().argumentTypes());
    }

    private static void assertPackedArrayOperationSignatures(
            FrontendForLoweringContract contract,
            GdccForPackedArrayIterType family
    ) {
        assertEquals(family.getInitIntrinsicName(), contract.init().intrinsicName());
        assertSame(family, contract.init().resultType());
        assertEquals(List.of(family.sourceType()), contract.init().argumentTypes());

        assertEquals(family.getShouldContinueIntrinsicName(), contract.shouldContinue().intrinsicName());
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(family), contract.shouldContinue().argumentTypes());

        assertEquals(family.getNextIntrinsicName(), contract.next().intrinsicName());
        assertSame(family, contract.next().resultType());
        assertEquals(List.of(family), contract.next().argumentTypes());

        assertEquals(family.getGetIntrinsicName(), contract.get().intrinsicName());
        assertEquals(family.elementType(), contract.get().resultType());
        assertEquals(List.of(family), contract.get().argumentTypes());
    }

    private static void assertFloatOperationSignatures(FrontendForLoweringContract contract) {
        var state = GdccForFloatIterType.FOR_FLOAT_ITER;

        assertEquals(ForLoweringContractRegistry.FLOAT_INIT_INTRINSIC, contract.init().intrinsicName());
        assertSame(state, contract.init().resultType());
        assertEquals(List.of(GdFloatType.FLOAT), contract.init().argumentTypes());

        assertEquals(
                ForLoweringContractRegistry.FLOAT_SHOULD_CONTINUE_INTRINSIC,
                contract.shouldContinue().intrinsicName()
        );
        assertSame(GdBoolType.BOOL, contract.shouldContinue().resultType());
        assertEquals(List.of(state), contract.shouldContinue().argumentTypes());

        assertEquals(ForLoweringContractRegistry.FLOAT_NEXT_INTRINSIC, contract.next().intrinsicName());
        assertSame(state, contract.next().resultType());
        assertEquals(List.of(state), contract.next().argumentTypes());

        assertEquals(ForLoweringContractRegistry.FLOAT_GET_INTRINSIC, contract.get().intrinsicName());
        assertSame(GdFloatType.FLOAT, contract.get().resultType());
        assertEquals(List.of(state), contract.get().argumentTypes());
    }
}
