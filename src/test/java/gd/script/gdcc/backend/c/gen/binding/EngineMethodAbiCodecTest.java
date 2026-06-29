package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.type.GdAABBType;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPackedStringArrayType;
import gd.script.gdcc.type.GdPackedVectorArrayType;
import gd.script.gdcc.type.GdPlaneType;
import gd.script.gdcc.type.GdProjectionType;
import gd.script.gdcc.type.GdQuaternionType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdRect2iType;
import gd.script.gdcc.type.GdRidType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdTransform2DType;
import gd.script.gdcc.type.GdTransform3DType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
    @DisplayName("codec should reject compiler-only types on engine helper ABI")
    void codecShouldRejectCompilerOnlyTypesOnEngineHelperAbi() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> EngineMethodAbiCodec.generate(new EngineMethodAbiSignature(
                        List.of(GdccForRangeIterType.FOR_RANGE_ITER),
                        GdVoidType.VOID,
                        false
                ))
        );

        assertTrue(ex.getMessage().contains("compiler-only type leaked into engine method ABI descriptor"), ex.getMessage());
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
