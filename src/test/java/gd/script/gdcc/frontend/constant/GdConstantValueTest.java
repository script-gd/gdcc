package gd.script.gdcc.frontend.constant;

import gd.script.gdcc.frontend.constant.GdConstantValue.BoolValue;
import gd.script.gdcc.frontend.constant.GdConstantValue.BuiltinValue;
import gd.script.gdcc.frontend.constant.GdConstantValue.FloatValue;
import gd.script.gdcc.frontend.constant.GdConstantValue.IntValue;
import gd.script.gdcc.frontend.constant.GdConstantValue.StringValue;
import gd.script.gdcc.type.GdAABBType;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPlaneType;
import gd.script.gdcc.type.GdProjectionType;
import gd.script.gdcc.type.GdQuaternionType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdRect2iType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdTransform2DType;
import gd.script.gdcc.type.GdTransform3DType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the frozen value-model contract of `GdConstantValue` (const plan §4.1/§5.3):
/// exact scalar types, the controlled-builtin leaf whitelist, canonical leaf counts, float32
/// narrowing normalization, int32 component bounds, and immutability — in both directions.
class GdConstantValueTest {

    // --- scalar value family ---

    @Test
    void scalarValuesCarryExactTypes() {
        assertAll(
                () -> assertEquals(GdConstantValue.BoolValue.class, new BoolValue(true).getClass()),
                () -> assertSame(GdBoolType.BOOL, new BoolValue(false).type()),
                () -> assertSame(GdIntType.INT, new IntValue(-1L).type()),
                () -> assertSame(GdFloatType.FLOAT, new FloatValue(0.5).type()),
                () -> assertSame(GdStringType.STRING, new StringValue("abc").type()),
                () -> assertEquals("abc", new StringValue("abc").value()),
                () -> assertThrows(NullPointerException.class, () -> new StringValue(null))
        );
    }

    // --- builtin leaf whitelist and layout counts ---

    @Test
    void builtinValueAcceptsEveryControlledTypeWithExactLeafCount() {
        record FloatCase(GdType type, int leafCount) {
        }

        var floatCases = List.of(
                new FloatCase(GdFloatVectorType.VECTOR2, 2),
                new FloatCase(GdFloatVectorType.VECTOR3, 3),
                new FloatCase(GdFloatVectorType.VECTOR4, 4),
                new FloatCase(GdRect2Type.RECT2, 4),
                new FloatCase(GdPlaneType.PLANE, 4),
                new FloatCase(GdQuaternionType.QUATERNION, 4),
                new FloatCase(GdAABBType.AABB, 6),
                new FloatCase(GdBasisType.BASIS, 9),
                new FloatCase(GdTransform2DType.TRANSFORM2D, 6),
                new FloatCase(GdTransform3DType.TRANSFORM3D, 12),
                new FloatCase(GdProjectionType.PROJECTION, 16),
                new FloatCase(GdColorType.COLOR, 4)
        );
        for (var floatCase : floatCases) {
            var leaves = new double[floatCase.leafCount()];
            for (var i = 0; i < leaves.length; i++) {
                leaves[i] = i + 0.5;
            }
            var value = BuiltinValue.ofFloats(floatCase.type(), leaves);
            assertAll(
                    floatCase.type().getTypeName(),
                    () -> assertSame(floatCase.type(), value.type()),
                    () -> assertEquals(floatCase.leafCount(), value.components().size()),
                    () -> assertTrue(value.components().stream()
                            .allMatch(FloatValue.class::isInstance))
            );
        }

        record IntCase(GdType type, int leafCount) {
        }

        var intCases = List.of(
                new IntCase(GdIntVectorType.VECTOR2I, 2),
                new IntCase(GdIntVectorType.VECTOR3I, 3),
                new IntCase(GdIntVectorType.VECTOR4I, 4),
                new IntCase(GdRect2iType.RECT2I, 4)
        );
        for (var intCase : intCases) {
            var leaves = new long[intCase.leafCount()];
            var value = BuiltinValue.ofInts(intCase.type(), leaves);
            assertAll(
                    intCase.type().getTypeName(),
                    () -> assertSame(intCase.type(), value.type()),
                    () -> assertEquals(intCase.leafCount(), value.components().size()),
                    () -> assertTrue(value.components().stream()
                            .allMatch(IntValue.class::isInstance))
            );
        }
    }

    @Test
    void builtinValueRejectsNonControlledTypes() {
        var rejectedTypes = List.of(
                GdIntType.INT,                       // scalars are not builtin aggregates
                GdFloatType.FLOAT,
                GdStringType.STRING,
                GdStringNameType.STRING_NAME,        // not the planned StringValue family
                GdVariantType.VARIANT,
                GdNilType.NIL,
                GdVoidType.VOID,                     // null extension-type path
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                GdPackedNumericArrayType.PACKED_INT32_ARRAY,
                new GdArrayType(GdVariantType.VARIANT),
                new GdObjectType("Node"),
                GdccForRangeIterType.FOR_RANGE_ITER  // compiler-only type, null extension type
        );

        for (var rejected : rejectedTypes) {
            var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new BuiltinValue(rejected, List.of()),
                    rejected.getTypeName()
            );
            assertTrue(
                    exception.getMessage().contains("not a controlled builtin constant type"),
                    rejected.getTypeName() + ": " + exception.getMessage()
            );
        }
    }

    @Test
    void builtinValueRejectsWrongLeafCount() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0, 2.0, 3.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> BuiltinValue.ofFloats(GdBasisType.BASIS,
                                1, 2, 3, 4, 5, 6, 7, 8)),        // 8 for 9 required
                () -> assertThrows(IllegalArgumentException.class,
                        () -> BuiltinValue.ofInts(GdIntVectorType.VECTOR3I, 1, 2, 3, 4)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> BuiltinValue.ofInts(GdRect2iType.RECT2I, 1, 2, 3))
        );
    }

    @Test
    void builtinValueRejectsWrongLeafKinds() {
        // Float-only types must not accept int, bool, string, or nested builtin leaves.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new BuiltinValue(
                        GdFloatVectorType.VECTOR2,
                        List.of(new IntValue(1), new FloatValue(2.0)))),
                () -> assertThrows(IllegalArgumentException.class, () -> new BuiltinValue(
                        GdFloatVectorType.VECTOR2,
                        List.of(new BoolValue(true), new FloatValue(2.0)))),
                () -> assertThrows(IllegalArgumentException.class, () -> new BuiltinValue(
                        GdFloatVectorType.VECTOR2,
                        List.of(new StringValue("x"), new FloatValue(2.0)))),
                () -> assertThrows(IllegalArgumentException.class, () -> new BuiltinValue(
                        GdFloatVectorType.VECTOR2,
                        List.of(
                                BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.0, 2.0),
                                new FloatValue(2.0)))),
                // Int-leaf types must not accept float leaves either.
                () -> assertThrows(IllegalArgumentException.class, () -> new BuiltinValue(
                        GdIntVectorType.VECTOR2I,
                        List.of(new FloatValue(1.0), new IntValue(2)))),
                // A null leaf is still a wrong-kind leaf, not an implicit zero.
                () -> {
                    var leaves = new ArrayList<GdConstantValue>();
                    leaves.add(null);
                    leaves.add(new FloatValue(2.0));
                    assertThrows(IllegalArgumentException.class,
                            () -> new BuiltinValue(GdFloatVectorType.VECTOR2, leaves));
                }
        );
    }

    // --- int32 component bounds ---

    @Test
    void builtinValueAcceptsInt32LeafEndpoints() {
        assertDoesNotThrow(() -> BuiltinValue.ofInts(
                GdIntVectorType.VECTOR2I,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE
        ));
    }

    @Test
    void builtinValueRejectsIntLeavesOutsideInt32Range() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> BuiltinValue.ofInts(
                        GdIntVectorType.VECTOR2I,
                        (long) Integer.MAX_VALUE + 1L,
                        0)),
                () -> assertThrows(IllegalArgumentException.class, () -> BuiltinValue.ofInts(
                        GdIntVectorType.VECTOR2I,
                        (long) Integer.MIN_VALUE - 1L,
                        0)),
                () -> assertThrows(IllegalArgumentException.class, () -> BuiltinValue.ofInts(
                        GdRect2iType.RECT2I,
                        0, 0, Long.MAX_VALUE, 0)),
                // The vector int leaves are int32 components, not int64 scalars.
                () -> assertThrows(IllegalArgumentException.class, () -> BuiltinValue.ofInts(
                        GdIntVectorType.VECTOR3I,
                        0, -4294967297L, 0))
        );
    }

    // --- float narrowing normalization (§4.1) ---

    @Test
    void builtinValueNormalizesFloatLeavesThroughFloat32Narrowing() {
        var value = BuiltinValue.ofFloats(
                GdFloatVectorType.VECTOR3,
                0.1,                                   // non float32-representable double
                -0.0,                                  // signed zero survives narrowing
                Double.POSITIVE_INFINITY               // non-finite survives narrowing
        );
        var x = assertInstanceOf(FloatValue.class, value.components().get(0));
        var y = assertInstanceOf(FloatValue.class, value.components().get(1));
        var z = assertInstanceOf(FloatValue.class, value.components().get(2));

        assertAll(
                () -> assertEquals((double) (float) 0.1, x.value()),
                () -> assertTrue(x.value() != 0.1,
                        "the stored leaf must already be the narrowed value, not the raw double"),
                () -> assertEquals(Double.doubleToRawLongBits(-0.0),
                        Double.doubleToRawLongBits(y.value())),
                () -> assertEquals(Double.POSITIVE_INFINITY, z.value())
        );
    }

    @Test
    void builtinValuePreservesNanLeaves() {
        var value = BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, Double.NaN, 1.0);
        var leaf = assertInstanceOf(FloatValue.class, value.components().getFirst());
        assertTrue(Double.isNaN(leaf.value()));
    }

    // --- canonical leaf ordering ---

    @Test
    void builtinValueKeepsCanonicalFlatLeafOrder() {
        // Transform3D: nine basis leaves then three origin leaves, source order preserved.
        var value = BuiltinValue.ofFloats(
                GdTransform3DType.TRANSFORM3D,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        );
        for (var i = 0; i < 12; i++) {
            var leaf = assertInstanceOf(FloatValue.class, value.components().get(i));
            assertEquals((double) (float) (i + 1), leaf.value(), "leaf[" + i + "]");
        }
    }

    // --- immutability ---

    @Test
    void builtinValueComponentsAreImmutableAndDefensivelyCopied() {
        var leaves = new ArrayList<GdConstantValue>();
        leaves.add(new FloatValue(1.0));
        leaves.add(new FloatValue(2.0));
        var value = new BuiltinValue(GdFloatVectorType.VECTOR2, leaves);
        leaves.add(new FloatValue(3.0));                // post-construction mutation of input

        assertAll(
                () -> assertEquals(2, value.components().size()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> value.components().add(new FloatValue(9.0))),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> value.components().set(0, new FloatValue(9.0)))
        );
    }

    // --- scalar leaves never equal builtin leaves ---

    @Test
    void builtinAndScalarValuesAreDistinctFacts() {
        var scalar = new FloatValue(1.5);
        var builtin = BuiltinValue.ofFloats(GdFloatVectorType.VECTOR2, 1.5, 1.5);

        assertAll(
                () -> assertFalse(scalar.type() instanceof GdFloatVectorType),
                () -> assertSame(GdFloatVectorType.VECTOR2, builtin.type()),
                () -> assertFalse(scalar.equals(builtin)),
                () -> assertFalse(builtin.equals(scalar))
        );
    }
}
