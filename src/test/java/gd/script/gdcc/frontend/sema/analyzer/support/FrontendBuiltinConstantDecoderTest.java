package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.ConstCallResult;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdAABBType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdPlaneType;
import gd.script.gdcc.type.GdProjectionType;
import gd.script.gdcc.type.GdQuaternionType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdRect2iType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdTransform2DType;
import gd.script.gdcc.type.GdTransform3DType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contracts for `FrontendBuiltinConstantDecoder`: restricted `TypeName(arg, ...)` metadata text
/// decodes into canonical `GdConstantValue`s while malformed shapes, wrong arities, unsupported
/// types, and out-of-range components all reject with a reason — never throw and never guess.
class FrontendBuiltinConstantDecoderTest {
    private static final @NotNull ClassRegistry REGISTRY = loadRegistry();

    @Test
    void decodesScalarConstantText() {
        assertAll(
                () -> assertEquals(new GdConstantValue.IntValue(42),
                        assertDecoded(decode(GdIntType.INT, "42"))),
                () -> assertEquals(new GdConstantValue.IntValue(Long.MIN_VALUE),
                        assertDecoded(decode(GdIntType.INT, "-9223372036854775808"))),
                () -> assertEquals(new GdConstantValue.IntValue(0),
                        assertDecoded(decode(GdIntType.INT, "0"))),
                () -> assertEquals(new GdConstantValue.FloatValue(0.1),
                        assertDecoded(decode(GdFloatType.FLOAT, "0.1"))),
                () -> assertEquals(new GdConstantValue.FloatValue(Double.POSITIVE_INFINITY),
                        assertDecoded(decode(GdFloatType.FLOAT, "inf"))),
                () -> assertEquals(new GdConstantValue.FloatValue(Double.NEGATIVE_INFINITY),
                        assertDecoded(decode(GdFloatType.FLOAT, "-inf"))),
                // `inf_neg` is the legacy compat spelling VariantWriter accepts.
                () -> assertEquals(new GdConstantValue.FloatValue(Double.NEGATIVE_INFINITY),
                        assertDecoded(decode(GdFloatType.FLOAT, "inf_neg"))),
                () -> assertEquals(new GdConstantValue.FloatValue(1e-320),
                        assertDecoded(decode(GdFloatType.FLOAT, "1e-320"))),
                () -> assertTrue(Double.isNaN(((GdConstantValue.FloatValue)
                        assertDecoded(decode(GdFloatType.FLOAT, "nan"))).value())),
                () -> assertEquals(new GdConstantValue.BoolValue(true),
                        assertDecoded(decode(GdBoolType.BOOL, "true"))),
                () -> assertEquals(new GdConstantValue.BoolValue(false),
                        assertDecoded(decode(GdBoolType.BOOL, "false")))
        );
    }

    @Test
    void decodesVectorAndScalarLeafCtorText() {
        assertAll(
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, -2},
                        decode(GdFloatVectorType.VECTOR2, "Vector2(1, -2)")),
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 2, 3},
                        decode(GdFloatVectorType.VECTOR3, "Vector3(1, 2, 3)")),
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR2,
                        new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},
                        decode(GdFloatVectorType.VECTOR2, "Vector2(inf, -inf)")),
                () -> assertIntLeaves(GdIntVectorType.VECTOR2I,
                        new long[]{Integer.MIN_VALUE, Integer.MAX_VALUE},
                        decode(GdIntVectorType.VECTOR2I, "Vector2i(-2147483648, 2147483647)")),
                () -> assertIntLeaves(GdIntVectorType.VECTOR4I, new long[]{1, 2, 3, 4},
                        decode(GdIntVectorType.VECTOR4I, "Vector4i(1, 2, 3, 4)")),
                () -> assertFloatLeaves(GdRect2Type.RECT2, new double[]{1, 2, 3, 4},
                        decode(GdRect2Type.RECT2, "Rect2(1, 2, 3, 4)")),
                () -> assertIntLeaves(GdRect2iType.RECT2I, new long[]{1, 2, 3, 4},
                        decode(GdRect2iType.RECT2I, "Rect2i(1, 2, 3, 4)")),
                () -> assertFloatLeaves(GdPlaneType.PLANE, new double[]{1, 0, 0, 0},
                        decode(GdPlaneType.PLANE, "Plane(1, 0, 0, 0)")),
                () -> assertFloatLeaves(GdQuaternionType.QUATERNION, new double[]{0, 0, 0, 1},
                        decode(GdQuaternionType.QUATERNION, "Quaternion(0, 0, 0, 1)")),
                () -> assertFloatLeaves(GdAABBType.AABB, new double[]{1, 2, 3, 4, 5, 6},
                        decode(GdAABBType.AABB, "AABB(1, 2, 3, 4, 5, 6)")),
                () -> assertFloatLeaves(GdColorType.COLOR, new double[]{0.5f, 0.25f, 0.125f, 1},
                        decode(GdColorType.COLOR, "Color(0.5, 0.25, 0.125, 1)"))
        );
    }

    @Test
    void flatEncodingsRespectCanonicalLayouts() {
        assertAll(
                // Transform2D metadata text is already column-oriented — no reordering.
                () -> assertFloatLeaves(GdTransform2DType.TRANSFORM2D, new double[]{1, 2, 3, 4, 5, 6},
                        decode(GdTransform2DType.TRANSFORM2D, "Transform2D(1, 2, 3, 4, 5, 6)")),
                // Basis text stores physical rows; canonical keeps logical columns — the
                // transpose maps flat[r*3+c] -> canonical[c*3+r].
                () -> assertFloatLeaves(GdBasisType.BASIS,
                        new double[]{1, 4, 7, 2, 5, 8, 3, 6, 9},
                        decode(GdBasisType.BASIS, "Basis(1, 2, 3, 4, 5, 6, 7, 8, 9)")),
                // Transform3D transposes the basis part and keeps the trailing origin flat.
                () -> assertFloatLeaves(GdTransform3DType.TRANSFORM3D,
                        new double[]{1, 4, 7, 2, 5, 8, 3, 6, 9, 10, 11, 12},
                        decode(GdTransform3DType.TRANSFORM3D,
                                "Transform3D(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)")),
                // Projection stores columns directly — all 16 in order.
                () -> assertFloatLeaves(GdProjectionType.PROJECTION,
                        new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                        decode(GdProjectionType.PROJECTION,
                                "Projection(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)"))
        );
    }

    @Test
    void malformedAndOutOfSurfaceTextsReject() {
        assertAll(
                // Grammar and shape failures.
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "not a ctor"), "malformed"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "Vector3(1, 2)"), "does not match"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "Vector2(1)"), "components"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR3, "Vector3(1, 2)"), "components"),
                () -> assertRejected(decode(GdBasisType.BASIS, "Basis(1, 2, 3)"), "components"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "Vector2(1,)"), "scalar literal"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "Vector2(1, x)"), "scalar literal"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "Vector2(0x1, 2)"), "scalar literal"),
                () -> assertRejected(decode(GdFloatVectorType.VECTOR2, "Vector2(Vector3(1,0,0), 2)"), "components"),
                // Int-leaf types reject float tokens and int64 values outside int32.
                () -> assertRejected(decode(GdIntVectorType.VECTOR2I, "Vector2i(1.0, 0)"), "integer literal"),
                () -> assertRejected(decode(GdIntVectorType.VECTOR2I, "Vector2i(2147483648, 0)"), "int32"),
                // Scalar text failures.
                () -> assertRejected(decode(GdIntType.INT, "1.5"), "malformed"),
                () -> assertRejected(decode(GdIntType.INT, "9223372036854775808"), "malformed"),
                () -> assertRejected(decode(GdFloatType.FLOAT, "abc"), "malformed"),
                () -> assertRejected(decode(GdFloatType.FLOAT, "0x1"), "malformed"),
                () -> assertRejected(decode(GdBoolType.BOOL, "1"), "malformed"),
                () -> assertRejected(decode(GdBoolType.BOOL, "TRUE"), "malformed"),
                // Types outside the constant surface reject instead of guessing.
                () -> assertRejected(decode(GdStringType.STRING, "String(abc)"), "not supported"),
                () -> assertRejected(decode(GdVariantType.VARIANT, "Variant(1)"), "not a constant value type")
        );
    }

    @Test
    void resolvesRealMetadataConstantsThroughClassRegistry() {
        assertAll(
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{0, 0},
                        resolve(GdFloatVectorType.VECTOR2, "ZERO")),
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR2, new double[]{1, 1},
                        resolve(GdFloatVectorType.VECTOR2, "ONE")),
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR2,
                        new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                        resolve(GdFloatVectorType.VECTOR2, "INF")),
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{0, 1, 0},
                        resolve(GdFloatVectorType.VECTOR3, "UP")),
                () -> assertFloatLeaves(GdFloatVectorType.VECTOR3, new double[]{1, 0, 0},
                        resolve(GdFloatVectorType.VECTOR3, "MODEL_LEFT")),
                () -> assertIntLeaves(GdIntVectorType.VECTOR2I,
                        new long[]{Integer.MIN_VALUE, Integer.MIN_VALUE},
                        resolve(GdIntVectorType.VECTOR2I, "MIN")),
                () -> assertIntLeaves(GdIntVectorType.VECTOR4I,
                        new long[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
                        resolve(GdIntVectorType.VECTOR4I, "MAX")),
                () -> assertFloatLeaves(GdPlaneType.PLANE, new double[]{1, 0, 0, 0},
                        resolve(GdPlaneType.PLANE, "PLANE_YZ")),
                () -> assertFloatLeaves(GdQuaternionType.QUATERNION, new double[]{0, 0, 0, 1},
                        resolve(GdQuaternionType.QUATERNION, "IDENTITY")),
                () -> assertFloatLeaves(GdBasisType.BASIS,
                        new double[]{-1, 0, 0, 0, 1, 0, 0, 0, 1},
                        resolve(GdBasisType.BASIS, "FLIP_X")),
                () -> assertFloatLeaves(GdTransform3DType.TRANSFORM3D,
                        new double[]{1, 0, 0, 0, -1, 0, 0, 0, 1, 0, 0, 0},
                        resolve(GdTransform3DType.TRANSFORM3D, "FLIP_Y")),
                () -> assertFloatLeaves(GdProjectionType.PROJECTION,
                        new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                        resolve(GdProjectionType.PROJECTION, "ZERO")),
                () -> assertFloatLeaves(GdColorType.COLOR, new double[]{1, 0, 1, 1},
                        resolve(GdColorType.COLOR, "MAGENTA"))
        );
    }

    @Test
    void unknownAndUnsupportedMetadataMembersStayNegative() {
        var axisX = FrontendBuiltinConstantDecoder.resolveConstant(
                REGISTRY, GdFloatVectorType.VECTOR2, "AXIS_X");
        var missing = FrontendBuiltinConstantDecoder.resolveConstant(
                REGISTRY, GdFloatVectorType.VECTOR2, "MISSING");
        assertAll(
                // Builtin-class enum members are not `constants` entries — they stay unknown.
                () -> assertInstanceOf(LookupContext.LookupResult.Unknown.class, axisX),
                () -> assertInstanceOf(LookupContext.LookupResult.Unknown.class, missing)
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static @NotNull ConstCallResult decode(@NotNull GdType type, @NotNull String text) {
        return FrontendBuiltinConstantDecoder.decodeConstantText(type, text);
    }

    private static @NotNull ConstCallResult resolve(@NotNull GdType type, @NotNull String member) {
        var result = FrontendBuiltinConstantDecoder.resolveConstant(REGISTRY, type, member);
        if (result instanceof LookupContext.LookupResult.ResolvedConstant resolved) {
            return new ConstCallResult.Resolved(resolved.value());
        }
        if (result instanceof LookupContext.LookupResult.NonConstant nonConstant) {
            return new ConstCallResult.Rejected(nonConstant.reason());
        }
        return new ConstCallResult.Rejected("lookup returned " + result.getClass().getSimpleName());
    }

    private static @NotNull GdConstantValue assertDecoded(@NotNull ConstCallResult result) {
        var resolved = assertInstanceOf(ConstCallResult.Resolved.class, result);
        return resolved.value();
    }

    private static void assertRejected(@NotNull ConstCallResult result, @Nullable String messagePart) {
        var rejected = assertInstanceOf(ConstCallResult.Rejected.class, result);
        if (messagePart != null) {
            assertTrue(
                    rejected.reason().contains(messagePart),
                    "Expected reason to contain '" + messagePart + "' but was: " + rejected.reason()
            );
        }
    }

    private static void assertFloatLeaves(
            @NotNull GdType type,
            double @NotNull [] expected,
            @NotNull ConstCallResult result
    ) {
        var value = assertInstanceOf(GdConstantValue.BuiltinValue.class, assertDecoded(result));
        assertEquals(type, value.type());
        assertEquals(expected.length, value.components().size());
        for (var index = 0; index < expected.length; index++) {
            var leaf = assertInstanceOf(
                    GdConstantValue.FloatValue.class, value.components().get(index));
            if (Double.isNaN(expected[index])) {
                assertTrue(Double.isNaN(leaf.value()), "component " + index + " should be NaN");
            } else {
                // Raw-bit comparison keeps -0.0 distinct from +0.0.
                assertEquals(
                        Double.doubleToRawLongBits((double) (float) expected[index]),
                        Double.doubleToRawLongBits(leaf.value()),
                        "component " + index + " of '" + type.getTypeName() + "'");
            }
        }
    }

    private static void assertIntLeaves(
            @NotNull GdType type,
            long @NotNull [] expected,
            @NotNull ConstCallResult result
    ) {
        var value = assertInstanceOf(GdConstantValue.BuiltinValue.class, assertDecoded(result));
        assertEquals(type, value.type());
        assertEquals(expected.length, value.components().size());
        for (var index = 0; index < expected.length; index++) {
            var leaf = assertInstanceOf(
                    GdConstantValue.IntValue.class, value.components().get(index));
            assertEquals(expected[index], leaf.value(),
                    "component " + index + " of '" + type.getTypeName() + "'");
        }
    }

    private static @NotNull ClassRegistry loadRegistry() {
        try {
            return new ClassRegistry(ExtensionApiLoader.loadDefault());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
