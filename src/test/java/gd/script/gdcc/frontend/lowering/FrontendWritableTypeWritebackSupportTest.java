package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPackedStringArrayType;
import gd.script.gdcc.type.GdPackedVectorArrayType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendWritableTypeWritebackSupportTest {
    @Test
    void requiresReverseCommitForCarrierTypeMatchesSharedTypeMatrix() {
        // Shared/reference families skip writeback on every route provenance.
        for (var provenance : FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.values()) {
            assertAll(
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(GdIntType.INT, provenance)),
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(GdObjectType.OBJECT, provenance)),
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            new GdArrayType(GdVariantType.VARIANT), provenance
                    )),
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), provenance
                    )),
                    // Variant keeps the conservative true answer: the runtime helper refines it later.
                    () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            GdVariantType.VARIANT, provenance
                    )),
                    () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            GdStringType.STRING, provenance
                    ))
            );
        }
    }

    @Test
    void packedCarrierAnswerDependsOnRouteProvenance() {
        // All ten packed families share the Variant-backed identity, so the per-route answer must
        // hold for every one of them, not just the numeric family.
        var packedFamilies = new gd.script.gdcc.type.GdPackedArrayType[]{
                GdPackedNumericArrayType.PACKED_BYTE_ARRAY,
                GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                GdPackedNumericArrayType.PACKED_INT64_ARRAY,
                GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY,
                GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY,
                GdPackedStringArrayType.PACKED_STRING_ARRAY,
                GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY,
                GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY,
                GdPackedVectorArrayType.PACKED_COLOR_ARRAY,
                GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY
        };
        for (var packedType : packedFamilies) {
            assertAll(
                    // Snapshot temps share identity with the source slot: no DIRECT_SLOT step.
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            packedType, FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.DIRECT_SLOT
                    )),
                    // Static leaf shares identity with static storage: no promotion step.
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            packedType, FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.STATIC_PROPERTY
                    )),
                    // Engine getter copy must not persist a mutating call: no writeback.
                    () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            packedType, FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.ENGINE_PROPERTY_CALL
                    )),
                    // Retained redundant-but-harmless routes keep the legacy true answer.
                    () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            packedType, FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.SCRIPT_PROPERTY
                    )),
                    () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            packedType, FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.CONTAINER_ELEMENT
                    )),
                    () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                            packedType, FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.GENERIC
                    ))
            );
        }
    }

    @Test
    void directSlotSnapshotCommitExemptsOnlyPackedCarriers() {
        assertAll(
                () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresDirectSlotSnapshotCommit(
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY
                )),
                () -> assertFalse(FrontendWritableTypeWritebackSupport.requiresDirectSlotSnapshotCommit(
                        GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY
                )),
                // Deliberately unconditional for every other family, even shared ones.
                () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresDirectSlotSnapshotCommit(
                        new GdArrayType(GdVariantType.VARIANT)
                )),
                () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresDirectSlotSnapshotCommit(GdStringType.STRING)),
                () -> assertTrue(FrontendWritableTypeWritebackSupport.requiresDirectSlotSnapshotCommit(GdVariantType.VARIANT))
        );
    }

    @Test
    void compilerOnlyTypeCannotEnterFrontendWritebackAnalysis() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(
                        GdccForRangeIterType.FOR_RANGE_ITER,
                        FrontendWritableTypeWritebackSupport.WritebackRouteProvenance.GENERIC
                )
        );
        var snapshotEx = assertThrows(
                IllegalArgumentException.class,
                () -> FrontendWritableTypeWritebackSupport.requiresDirectSlotSnapshotCommit(GdccForRangeIterType.FOR_RANGE_ITER)
        );

        assertAll(
                () -> assertTrue(ex.getMessage().contains("compiler-only type leaked into frontend writeback analysis"), ex.getMessage()),
                () -> assertTrue(snapshotEx.getMessage().contains("compiler-only type leaked into frontend writeback analysis"), snapshotEx.getMessage())
        );
    }
}
