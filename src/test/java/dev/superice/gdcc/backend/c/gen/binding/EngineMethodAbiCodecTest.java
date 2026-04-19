package dev.superice.gdcc.backend.c.gen.binding;

import dev.superice.gdcc.type.GdAABBType;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdBasisType;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdCallableType;
import dev.superice.gdcc.type.GdColorType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdFloatVectorType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdIntVectorType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdNodePathType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdPackedStringArrayType;
import dev.superice.gdcc.type.GdPackedVectorArrayType;
import dev.superice.gdcc.type.GdPlaneType;
import dev.superice.gdcc.type.GdProjectionType;
import dev.superice.gdcc.type.GdQuaternionType;
import dev.superice.gdcc.type.GdRect2Type;
import dev.superice.gdcc.type.GdRect2iType;
import dev.superice.gdcc.type.GdRidType;
import dev.superice.gdcc.type.GdSignalType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdTransform2DType;
import dev.superice.gdcc.type.GdTransform3DType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineMethodAbiCodecTest {
    @Test
    @DisplayName("codec should generate the documented descriptor examples")
    void codecShouldGenerateTheDocumentedDescriptorExamples() {
        assertEquals(
                "PL4Node_ZI_RV",
                EngineMethodAbiCodec.generate(new EngineMethodAbiSignature(
                        List.of(new GdObjectType("Node"), GdBoolType.BOOL, GdIntType.INT),
                        GdVoidType.VOID,
                        false
                ))
        );
        assertEquals(
                "PS_RR_Xv",
                EngineMethodAbiCodec.generate(new EngineMethodAbiSignature(
                        List.of(GdStringNameType.STRING_NAME),
                        GdVariantType.VARIANT,
                        true
                ))
        );
    }

    @Test
    @DisplayName("codec should round-trip nested container signatures and object names with underscores")
    void codecShouldRoundTripNestedContainerSignaturesAndObjectNamesWithUnderscores() {
        var signature = new EngineMethodAbiSignature(
                List.of(
                        new GdArrayType(new GdObjectType("My_Node")),
                        new GdDictionaryType(
                                GdStringNameType.STRING_NAME,
                                new GdArrayType(new GdDictionaryType(GdIntType.INT, new GdObjectType("Scene_Node")))
                        )
                ),
                new GdDictionaryType(new GdObjectType("Node_2"), GdVariantType.VARIANT),
                true
        );

        var descriptor = EngineMethodAbiCodec.generate(signature);

        assertTrue(descriptor.contains("L7My_Node_"), descriptor);
        assertTrue(descriptor.contains("L10Scene_Node_"), descriptor);
        assertTrue(descriptor.contains("L6Node_2_"), descriptor);
        assertEquals(signature, EngineMethodAbiCodec.parse(descriptor));
        assertEquals(descriptor, EngineMethodAbiCodec.generate(EngineMethodAbiCodec.parse(descriptor)));
    }

    @Test
    @DisplayName("codec should keep leaf non-object codes unique and single-letter")
    void codecShouldKeepLeafNonObjectCodesUniqueAndSingleLetter() {
        var leafTypes = List.of(
                GdNilType.NIL,
                GdVariantType.VARIANT,
                GdBoolType.BOOL,
                GdIntType.INT,
                GdFloatType.FLOAT,
                GdStringType.STRING,
                GdFloatVectorType.VECTOR2,
                GdIntVectorType.VECTOR2I,
                GdRect2Type.RECT2,
                GdRect2iType.RECT2I,
                GdFloatVectorType.VECTOR3,
                GdIntVectorType.VECTOR3I,
                GdTransform2DType.TRANSFORM2D,
                GdFloatVectorType.VECTOR4,
                GdIntVectorType.VECTOR4I,
                GdPlaneType.PLANE,
                GdQuaternionType.QUATERNION,
                GdAABBType.AABB,
                GdBasisType.BASIS,
                GdTransform3DType.TRANSFORM3D,
                GdProjectionType.PROJECTION,
                GdColorType.COLOR,
                GdStringNameType.STRING_NAME,
                GdNodePathType.NODE_PATH,
                GdRidType.RID,
                new GdCallableType(),
                new GdSignalType(),
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
        );

        var seenCodes = new HashSet<String>();
        for (var leafType : leafTypes) {
            var descriptor = EngineMethodAbiCodec.generate(new EngineMethodAbiSignature(List.of(leafType), GdVoidType.VOID, false));
            var code = descriptor.substring(1, descriptor.indexOf("_R"));
            assertEquals(1, code.length(), descriptor);
            assertTrue(seenCodes.add(code), () -> "Duplicate leaf code for " + leafType.getTypeName() + ": " + code);
        }
    }

    @Test
    @DisplayName("codec should reject malformed descriptors instead of guessing")
    void codecShouldRejectMalformedDescriptorsInsteadOfGuessing() {
        assertInvalid("");
        assertInvalid("I_RV");
        assertInvalid("P_R");
        assertInvalid("PLNode__RV");
        assertInvalid("PL4Node_RV");
        assertInvalid("PAI_RV");
        assertInvalid("PDI_RV");
        assertInvalid("P_RV_Xn");
        assertInvalid("P_RV_extra");
        assertInvalid("PL4Node__Xv");
    }

    private static void assertInvalid(@NotNull String descriptor) {
        assertThrows(IllegalArgumentException.class, () -> EngineMethodAbiCodec.parse(descriptor), descriptor);
    }
}
