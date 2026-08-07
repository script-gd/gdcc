package gd.script.gdcc.util.type;

import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdAABBType;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdExtensionTypeEnum;
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
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract coverage for the shared explicit-cast classifier.
///
/// Builtin pairs freeze Godot {@code Variant::can_convert} (not strict; identical on 4.5.1 and
/// 4.7.1). Object cases freeze same-chain bidirectional rules. Parameterized containers freeze
/// base-only parity.
class ExplicitCastSupportTest {
    private static ClassRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("builtinCastCases")
    void classifiesBuiltinPairs(
            @SuppressWarnings("unused") String sourceLabel,
            @SuppressWarnings("unused") String targetLabel,
            ExplicitCastDecision expected,
            GdType source,
            GdType target
    ) {
        assertEquals(
                expected,
                ExplicitCastSupport.classify(registry, source, target),
                () -> "source=" + source.getTypeName()
                        + " target=" + target.getTypeName()
                        + " expected=" + expected
        );
    }

    @Test
    void variantTargetPacksNonVariantAndKeepsVariantIdentity() {
        assertEquals(
                ExplicitCastDecision.IDENTITY,
                ExplicitCastSupport.classify(registry, GdVariantType.VARIANT, GdVariantType.VARIANT)
        );
        assertEquals(
                ExplicitCastDecision.PACK_TO_VARIANT,
                ExplicitCastSupport.classify(registry, GdIntType.INT, GdVariantType.VARIANT)
        );
        assertEquals(
                ExplicitCastDecision.PACK_TO_VARIANT,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), GdVariantType.VARIANT)
        );
        assertEquals(
                ExplicitCastDecision.PACK_TO_VARIANT,
                ExplicitCastSupport.classify(registry, GdNilType.NIL, GdVariantType.VARIANT)
        );
    }

    @Test
    void exactSameTypesAreIdentity() {
        assertEquals(
                ExplicitCastDecision.IDENTITY,
                ExplicitCastSupport.classify(registry, GdIntType.INT, GdIntType.INT)
        );
        assertEquals(
                ExplicitCastDecision.IDENTITY,
                ExplicitCastSupport.classify(registry, new GdArrayType(GdIntType.INT), new GdArrayType(GdIntType.INT))
        );
        assertEquals(
                ExplicitCastDecision.IDENTITY,
                ExplicitCastSupport.classify(
                        registry,
                        new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                        new GdDictionaryType(GdStringType.STRING, GdIntType.INT)
                )
        );
        assertEquals(
                ExplicitCastDecision.IDENTITY,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), new GdObjectType("Node"))
        );
    }

    @Test
    void objectSameChainUpcastAndDowncast() {
        assertEquals(
                ExplicitCastDecision.OBJECT_UPCAST,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node2D"), new GdObjectType("Node"))
        );
        assertEquals(
                ExplicitCastDecision.OBJECT_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), new GdObjectType("Node2D"))
        );
        assertEquals(
                ExplicitCastDecision.OBJECT_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdNilType.NIL, new GdObjectType("Node"))
        );
        assertEquals(
                ExplicitCastDecision.OBJECT_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdVariantType.VARIANT, new GdObjectType("Node"))
        );
    }

    @Test
    void unrelatedObjectClassesAreInvalid() {
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), new GdObjectType("RefCounted"))
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, new GdObjectType("RefCounted"), new GdObjectType("Node"))
        );
    }

    @Test
    void unknownObjectClassNamesFailClosed() {
        // Unregistered names: same text is exact identity; different names cannot prove a chain.
        assertEquals(
                ExplicitCastDecision.IDENTITY,
                ExplicitCastSupport.classify(
                        registry,
                        new GdObjectType("UnknownCastClassA"),
                        new GdObjectType("UnknownCastClassA")
                )
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(
                        registry,
                        new GdObjectType("UnknownCastClassA"),
                        new GdObjectType("UnknownCastClassB")
                )
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), new GdObjectType("UnknownCastClassX"))
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, new GdObjectType("UnknownCastClassX"), new GdObjectType("Node"))
        );
    }

    @Test
    void hardNonObjectToObjectIsInvalid() {
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, GdIntType.INT, new GdObjectType("Node"))
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, GdStringType.STRING, new GdObjectType("Node"))
        );
    }

    @Test
    void objectToRidIsBuiltinCastAndNilToRidIsInvalid() {
        // Godot can_convert: OBJECT→RID true; NIL→RID false (only NIL→OBJECT is allowed for NIL source).
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), GdRidType.RID)
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, GdNilType.NIL, GdRidType.RID)
        );
    }

    @Test
    void parameterizedContainerBaseOnlyParity() {
        var genericArray = new GdArrayType(GdVariantType.VARIANT);
        var intArray = new GdArrayType(GdIntType.INT);
        var stringArray = new GdArrayType(GdStringType.STRING);
        var genericDict = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var stringIntDict = new GdDictionaryType(GdStringType.STRING, GdIntType.INT);
        var intStringDict = new GdDictionaryType(GdIntType.INT, GdStringType.STRING);

        // Parameterized → generic bare keeps representation (AssignInsn / IDENTITY).
        assertEquals(ExplicitCastDecision.IDENTITY, ExplicitCastSupport.classify(registry, intArray, genericArray));
        assertEquals(ExplicitCastDecision.IDENTITY, ExplicitCastSupport.classify(registry, stringIntDict, genericDict));

        // Generic / different-parameter / Variant → parameterized: base ARRAY/DICTIONARY cast.
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, genericArray, intArray)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, intArray, stringArray)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdVariantType.VARIANT, intArray)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, genericDict, stringIntDict)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, stringIntDict, intStringDict)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdVariantType.VARIANT, stringIntDict)
        );

        // Base-only: classifier ignores type arguments and never rejects different-parameter pairs.
        assertTrue(ExplicitCastSupport.checkAllowed(registry, intArray, stringArray));
        assertTrue(ExplicitCastSupport.checkAllowed(registry, stringIntDict, intStringDict));
    }

    @Test
    void arrayPackedFamilyBidirectional() {
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(
                        registry,
                        new GdArrayType(GdVariantType.VARIANT),
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY
                )
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(
                        registry,
                        GdPackedStringArrayType.PACKED_STRING_ARRAY,
                        new GdArrayType(GdVariantType.VARIANT)
                )
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(
                        registry,
                        GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                        GdPackedStringArrayType.PACKED_STRING_ARRAY
                )
        );
    }

    @Test
    void voidAndNilTargetsAreInvalid() {
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, GdIntType.INT, GdVoidType.VOID)
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, GdVoidType.VOID, GdIntType.INT)
        );
        assertEquals(
                ExplicitCastDecision.INVALID,
                ExplicitCastSupport.classify(registry, GdIntType.INT, GdNilType.NIL)
        );
    }

    @Test
    void compilerOnlyTypesFailFast() {
        var message = assertThrows(
                IllegalArgumentException.class,
                () -> ExplicitCastSupport.classify(registry, GdccForRangeIterType.FOR_RANGE_ITER, GdIntType.INT)
        ).getMessage();
        assertTrue(message.contains("compiler-only type"));
        assertTrue(message.contains("explicit cast source type"));

        message = assertThrows(
                IllegalArgumentException.class,
                () -> ExplicitCastSupport.classify(registry, GdIntType.INT, GdccForRangeIterType.FOR_RANGE_ITER)
        ).getMessage();
        assertTrue(message.contains("compiler-only type"));
        assertTrue(message.contains("explicit cast target type"));
    }

    @Test
    void doesNotReuseImplicitBoundaryStrictness() {
        // Implicit matrix rejects String→NodePath / int→Color / Object→RID; explicit as allows them.
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdStringType.STRING, GdNodePathType.NODE_PATH)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdIntType.INT, GdColorType.COLOR)
        );
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, new GdObjectType("Object"), GdRidType.RID)
        );
        // Implicit allows only limited widening; explicit also allows float→int via can_convert.
        assertEquals(
                ExplicitCastDecision.BUILTIN_RUNTIME_CAST,
                ExplicitCastSupport.classify(registry, GdFloatType.FLOAT, GdIntType.INT)
        );
        // Assignment-compatible upcast alone is not the full as rule: downcast stays runtime cast.
        assertNotEquals(
                ExplicitCastDecision.OBJECT_UPCAST,
                ExplicitCastSupport.classify(registry, new GdObjectType("Node"), new GdObjectType("Node2D"))
        );
    }

    @Test
    void canConvertMatrixMatchesGodotIdentityAndNilRules() {
        assertTrue(ExplicitCastSupport.canConvert(GdExtensionTypeEnum.INT, GdExtensionTypeEnum.INT));
        assertTrue(ExplicitCastSupport.canConvert(GdExtensionTypeEnum.BOOL, GdExtensionTypeEnum.NIL));
        assertTrue(ExplicitCastSupport.canConvert(GdExtensionTypeEnum.NIL, GdExtensionTypeEnum.OBJECT));
        assertEquals(false, ExplicitCastSupport.canConvert(GdExtensionTypeEnum.NIL, GdExtensionTypeEnum.RID));
        assertEquals(false, ExplicitCastSupport.canConvert(GdExtensionTypeEnum.OBJECT, GdExtensionTypeEnum.STRING));
        assertEquals(false, ExplicitCastSupport.canConvert(GdExtensionTypeEnum.INT, GdExtensionTypeEnum.DICTIONARY));
    }

    static Stream<Arguments> builtinCastCases() {
        var cases = new ArrayList<Arguments>();

        // bool / int / float / String family
        add(cases, "bool", "int", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdBoolType.BOOL, GdIntType.INT);
        add(cases, "bool", "float", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdBoolType.BOOL, GdFloatType.FLOAT);
        add(cases, "bool", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdBoolType.BOOL, GdStringType.STRING);
        add(cases, "int", "bool", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntType.INT, GdBoolType.BOOL);
        add(cases, "int", "float", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntType.INT, GdFloatType.FLOAT);
        add(cases, "int", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntType.INT, GdStringType.STRING);
        add(cases, "int", "Color", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntType.INT, GdColorType.COLOR);
        add(cases, "float", "bool", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatType.FLOAT, GdBoolType.BOOL);
        add(cases, "float", "int", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatType.FLOAT, GdIntType.INT);
        add(cases, "float", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatType.FLOAT, GdStringType.STRING);
        add(cases, "String", "bool", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringType.STRING, GdBoolType.BOOL);
        add(cases, "String", "int", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringType.STRING, GdIntType.INT);
        add(cases, "String", "float", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringType.STRING, GdFloatType.FLOAT);
        add(cases, "String", "StringName", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringType.STRING, GdStringNameType.STRING_NAME);
        add(cases, "String", "NodePath", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringType.STRING, GdNodePathType.NODE_PATH);
        add(cases, "String", "Color", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringType.STRING, GdColorType.COLOR);

        // Vector / Rect pairs
        add(cases, "Vector2", "Vector2i", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatVectorType.VECTOR2, GdIntVectorType.VECTOR2I);
        add(cases, "Vector2i", "Vector2", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntVectorType.VECTOR2I, GdFloatVectorType.VECTOR2);
        add(cases, "Rect2", "Rect2i", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdRect2Type.RECT2, GdRect2iType.RECT2I);
        add(cases, "Rect2i", "Rect2", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdRect2iType.RECT2I, GdRect2Type.RECT2);
        add(cases, "Vector3", "Vector3i", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatVectorType.VECTOR3, GdIntVectorType.VECTOR3I);
        add(cases, "Vector3i", "Vector3", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntVectorType.VECTOR3I, GdFloatVectorType.VECTOR3);
        add(cases, "Vector4", "Vector4i", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatVectorType.VECTOR4, GdIntVectorType.VECTOR4I);
        add(cases, "Vector4i", "Vector4", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdIntVectorType.VECTOR4I, GdFloatVectorType.VECTOR4);

        // Basis / Quaternion / Transform / Projection
        add(cases, "Basis", "Quaternion", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdBasisType.BASIS, GdQuaternionType.QUATERNION);
        add(cases, "Quaternion", "Basis", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdQuaternionType.QUATERNION, GdBasisType.BASIS);
        add(cases, "Transform2D", "Transform3D", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdTransform2DType.TRANSFORM2D, GdTransform3DType.TRANSFORM3D);
        add(cases, "Transform3D", "Transform2D", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdTransform3DType.TRANSFORM3D, GdTransform2DType.TRANSFORM2D);
        add(cases, "Transform3D", "Projection", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdTransform3DType.TRANSFORM3D, GdProjectionType.PROJECTION);
        add(cases, "Projection", "Transform3D", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdProjectionType.PROJECTION, GdTransform3DType.TRANSFORM3D);
        add(cases, "Basis", "Transform3D", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdBasisType.BASIS, GdTransform3DType.TRANSFORM3D);
        add(cases, "Quaternion", "Transform3D", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdQuaternionType.QUATERNION, GdTransform3DType.TRANSFORM3D);
        add(cases, "Transform3D", "Basis", ExplicitCastDecision.INVALID, GdTransform3DType.TRANSFORM3D, GdBasisType.BASIS);
        add(cases, "Transform3D", "Quaternion", ExplicitCastDecision.INVALID, GdTransform3DType.TRANSFORM3D, GdQuaternionType.QUATERNION);

        // Array ↔ packed family (representative members)
        var genericArray = new GdArrayType(GdVariantType.VARIANT);
        add(cases, "Array", "PackedByteArray", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedNumericArrayType.PACKED_BYTE_ARRAY);
        add(cases, "Array", "PackedInt32Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        add(cases, "Array", "PackedInt64Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedNumericArrayType.PACKED_INT64_ARRAY);
        add(cases, "Array", "PackedFloat32Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY);
        add(cases, "Array", "PackedFloat64Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY);
        add(cases, "Array", "PackedStringArray", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedStringArrayType.PACKED_STRING_ARRAY);
        add(cases, "Array", "PackedVector2Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY);
        add(cases, "Array", "PackedVector3Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY);
        add(cases, "Array", "PackedColorArray", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedVectorArrayType.PACKED_COLOR_ARRAY);
        add(cases, "Array", "PackedVector4Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, genericArray, GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY);
        add(cases, "PackedByteArray", "Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdPackedNumericArrayType.PACKED_BYTE_ARRAY, genericArray);
        add(cases, "PackedStringArray", "Array", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdPackedStringArrayType.PACKED_STRING_ARRAY, genericArray);

        // Variant → builtin runtime cast
        add(cases, "Variant", "int", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdVariantType.VARIANT, GdIntType.INT);
        add(cases, "Variant", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdVariantType.VARIANT, GdStringType.STRING);
        add(cases, "Variant", "Array[int]", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdVariantType.VARIANT, new GdArrayType(GdIntType.INT));

        // Godot-rejected unrelated pairs
        add(cases, "Vector2", "int", ExplicitCastDecision.INVALID, GdFloatVectorType.VECTOR2, GdIntType.INT);
        add(cases, "Vector2", "Vector3", ExplicitCastDecision.INVALID, GdFloatVectorType.VECTOR2, GdFloatVectorType.VECTOR3);
        add(cases, "Plane", "Transform3D", ExplicitCastDecision.INVALID, GdPlaneType.PLANE, GdTransform3DType.TRANSFORM3D);
        add(cases, "AABB", "Transform3D", ExplicitCastDecision.INVALID, GdAABBType.AABB, GdTransform3DType.TRANSFORM3D);
        add(cases, "Array", "Dictionary", ExplicitCastDecision.INVALID, genericArray, new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT));
        add(cases, "Dictionary", "Array", ExplicitCastDecision.INVALID, new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), genericArray);
        add(cases, "int", "Dictionary", ExplicitCastDecision.INVALID, GdIntType.INT, new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT));
        add(cases, "int", "Callable", ExplicitCastDecision.INVALID, GdIntType.INT, new GdCallableType());
        add(cases, "int", "Signal", ExplicitCastDecision.INVALID, GdIntType.INT, new GdSignalType());
        add(cases, "Object", "String", ExplicitCastDecision.INVALID, new GdObjectType("Object"), GdStringType.STRING);
        add(cases, "Object", "Array", ExplicitCastDecision.INVALID, new GdObjectType("Object"), genericArray);
        add(cases, "Nil", "int", ExplicitCastDecision.INVALID, GdNilType.NIL, GdIntType.INT);
        add(cases, "Nil", "String", ExplicitCastDecision.INVALID, GdNilType.NIL, GdStringType.STRING);
        // STRING target accepts every builtin source except OBJECT; reverse Vector2 target does not accept STRING.
        add(cases, "String", "Vector2", ExplicitCastDecision.INVALID, GdStringType.STRING, GdFloatVectorType.VECTOR2);
        add(cases, "Dictionary", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), GdStringType.STRING);
        add(cases, "Vector2", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdFloatVectorType.VECTOR2, GdStringType.STRING);
        add(cases, "StringName", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdStringNameType.STRING_NAME, GdStringType.STRING);
        add(cases, "NodePath", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdNodePathType.NODE_PATH, GdStringType.STRING);
        add(cases, "RID", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, GdRidType.RID, GdStringType.STRING);
        add(cases, "Callable", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, new GdCallableType(), GdStringType.STRING);
        add(cases, "Signal", "String", ExplicitCastDecision.BUILTIN_RUNTIME_CAST, new GdSignalType(), GdStringType.STRING);
        add(cases, "StringName", "NodePath", ExplicitCastDecision.INVALID, GdStringNameType.STRING_NAME, GdNodePathType.NODE_PATH);
        add(cases, "RID", "Object", ExplicitCastDecision.INVALID, GdRidType.RID, new GdObjectType("Object"));

        return cases.stream();
    }

    private static void add(
            List<Arguments> cases,
            String sourceLabel,
            String targetLabel,
            ExplicitCastDecision decision,
            GdType source,
            GdType target
    ) {
        cases.add(Arguments.of(sourceLabel, targetLabel, decision, source, target));
    }
}
