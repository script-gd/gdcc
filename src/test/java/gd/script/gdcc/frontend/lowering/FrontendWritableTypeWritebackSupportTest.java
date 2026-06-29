package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendWritableTypeWritebackSupportTest {
    @Test
    void requiresReverseCommitForCarrierTypeMatchesSharedTypeMatrix() {
        assertAll(
                () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(GdIntType.INT)),
                () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(GdObjectType.OBJECT)),
                () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                        new GdArrayType(GdVariantType.VARIANT)
                )),
                () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                        new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT)
                )),
                () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY
                )),
                () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                        GdVariantType.VARIANT
                ))
        );
    }

    @Test
    void compilerOnlyTypeCannotEnterFrontendWritebackAnalysis() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                        GdccForRangeIterType.FOR_RANGE_ITER
                )
        );

        assertTrue(ex.getMessage().contains("compiler-only type leaked into frontend writeback analysis"), ex.getMessage());
    }
}
