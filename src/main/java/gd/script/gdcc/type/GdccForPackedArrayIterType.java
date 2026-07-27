package gd.script.gdcc.type;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Compiler-only storage type for one specialized Packed*Array `for-in` iterator family.
///
/// Each concrete Packed*Array has its own state instance (no shared kind-tagged union). The C storage
/// owns a typed COW snapshot, a typed element base pointer, index, and size. Copy bumps the COW
/// handle; get reads the typed pointer without runtime family dispatch.
public final class GdccForPackedArrayIterType implements GdCompilerType {
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_BYTE_ARRAY_ITER =
            new GdccForPackedArrayIterType("byte_array", "ByteArray", GdPackedNumericArrayType.PACKED_BYTE_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_INT32_ARRAY_ITER =
            new GdccForPackedArrayIterType("int32_array", "Int32Array", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_INT64_ARRAY_ITER =
            new GdccForPackedArrayIterType("int64_array", "Int64Array", GdPackedNumericArrayType.PACKED_INT64_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_FLOAT32_ARRAY_ITER =
            new GdccForPackedArrayIterType("float32_array", "Float32Array", GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_FLOAT64_ARRAY_ITER =
            new GdccForPackedArrayIterType("float64_array", "Float64Array", GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_STRING_ARRAY_ITER =
            new GdccForPackedArrayIterType("string_array", "StringArray", GdPackedStringArrayType.PACKED_STRING_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_VECTOR2_ARRAY_ITER =
            new GdccForPackedArrayIterType("vector2_array", "Vector2Array", GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_VECTOR3_ARRAY_ITER =
            new GdccForPackedArrayIterType("vector3_array", "Vector3Array", GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_VECTOR4_ARRAY_ITER =
            new GdccForPackedArrayIterType("vector4_array", "Vector4Array", GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY);
    public static final @NotNull GdccForPackedArrayIterType FOR_PACKED_COLOR_ARRAY_ITER =
            new GdccForPackedArrayIterType("color_array", "ColorArray", GdPackedVectorArrayType.PACKED_COLOR_ARRAY);

    private static final @NotNull List<GdccForPackedArrayIterType> ALL = List.of(
            FOR_PACKED_BYTE_ARRAY_ITER,
            FOR_PACKED_INT32_ARRAY_ITER,
            FOR_PACKED_INT64_ARRAY_ITER,
            FOR_PACKED_FLOAT32_ARRAY_ITER,
            FOR_PACKED_FLOAT64_ARRAY_ITER,
            FOR_PACKED_STRING_ARRAY_ITER,
            FOR_PACKED_VECTOR2_ARRAY_ITER,
            FOR_PACKED_VECTOR3_ARRAY_ITER,
            FOR_PACKED_VECTOR4_ARRAY_ITER,
            FOR_PACKED_COLOR_ARRAY_ITER
    );

    private static final @NotNull Map<GdPackedArrayType, GdccForPackedArrayIterType> BY_SOURCE =
            ALL.stream().collect(Collectors.toUnmodifiableMap(
                    GdccForPackedArrayIterType::sourceType,
                    Function.identity()
            ));

    private static final @NotNull Map<String, GdccForPackedArrayIterType> BY_LIR_TEXT =
            ALL.stream().collect(Collectors.toUnmodifiableMap(
                    GdccForPackedArrayIterType::getLirTypeText,
                    Function.identity()
            ));

    private final @NotNull String familySlug;
    private final @NotNull String typeNamePascal;
    private final @NotNull GdPackedArrayType sourceType;
    private final @NotNull String lirTypeText;
    private final @NotNull String cStorageTypeName;
    private final @NotNull String cInitHelperName;
    private final @NotNull String cDestroyHelperName;
    private final @NotNull String cCopyHelperName;
    private final @NotNull String cFromHelperName;
    private final @NotNull String cShouldContinueHelperName;
    private final @NotNull String cNextHelperName;
    private final @NotNull String cGetHelperName;
    private final @NotNull String initIntrinsicName;
    private final @NotNull String shouldContinueIntrinsicName;
    private final @NotNull String nextIntrinsicName;
    private final @NotNull String getIntrinsicName;

    private GdccForPackedArrayIterType(
            @NotNull String familySlug,
            @NotNull String typeNamePascal,
            @NotNull GdPackedArrayType sourceType
    ) {
        this.familySlug = familySlug;
        this.typeNamePascal = typeNamePascal;
        this.sourceType = sourceType;
        var typeName = "GdccForPacked" + typeNamePascal + "Iter";
        this.lirTypeText = "compiler::" + typeName;
        var cPrefix = "gdcc_for_packed_" + familySlug + "_iter";
        this.cStorageTypeName = cPrefix;
        this.cInitHelperName = cPrefix + "_init";
        this.cDestroyHelperName = cPrefix + "_destroy";
        this.cCopyHelperName = cPrefix + "_copy";
        this.cFromHelperName = cPrefix + "_from";
        this.cShouldContinueHelperName = cPrefix + "_should_continue";
        this.cNextHelperName = cPrefix + "_next";
        this.cGetHelperName = cPrefix + "_get";
        var intrinsicPrefix = "gdcc.for_packed_" + familySlug + "_iter";
        this.initIntrinsicName = intrinsicPrefix + ".init";
        this.shouldContinueIntrinsicName = intrinsicPrefix + ".should_continue";
        this.nextIntrinsicName = intrinsicPrefix + ".next";
        this.getIntrinsicName = intrinsicPrefix + ".get";
    }

    public static @NotNull List<GdccForPackedArrayIterType> all() {
        return ALL;
    }

    public static @NotNull GdccForPackedArrayIterType of(@NotNull GdPackedArrayType sourceType) {
        var type = BY_SOURCE.get(Objects.requireNonNull(sourceType, "sourceType must not be null"));
        if (type == null) {
            throw new IllegalArgumentException("unsupported packed array type: " + sourceType.getTypeName());
        }
        return type;
    }

    public static @Nullable GdccForPackedArrayIterType findByLirTypeText(@NotNull String lirTypeText) {
        return BY_LIR_TEXT.get(lirTypeText);
    }

    public static @NotNull GdccForPackedArrayIterType requireByLirTypeText(@NotNull String lirTypeText) {
        var type = findByLirTypeText(lirTypeText);
        if (type == null) {
            throw new IllegalArgumentException("unknown packed array iterator LIR type: " + lirTypeText);
        }
        return type;
    }

    public @NotNull String familySlug() {
        return familySlug;
    }

    public @NotNull String typeNamePascal() {
        return typeNamePascal;
    }

    public @NotNull GdPackedArrayType sourceType() {
        return sourceType;
    }

    public @NotNull GdType elementType() {
        return sourceType.getValueType();
    }

    public @NotNull String getCFromHelperName() {
        return cFromHelperName;
    }

    public @NotNull String getCShouldContinueHelperName() {
        return cShouldContinueHelperName;
    }

    public @NotNull String getCNextHelperName() {
        return cNextHelperName;
    }

    public @NotNull String getCGetHelperName() {
        return cGetHelperName;
    }

    public @NotNull String getInitIntrinsicName() {
        return initIntrinsicName;
    }

    public @NotNull String getShouldContinueIntrinsicName() {
        return shouldContinueIntrinsicName;
    }

    public @NotNull String getNextIntrinsicName() {
        return nextIntrinsicName;
    }

    public @NotNull String getGetIntrinsicName() {
        return getIntrinsicName;
    }

    @Override
    public @NotNull String getTypeName() {
        return "GdccForPacked" + typeNamePascal + "Iter";
    }

    @Override
    public @NotNull String getLirTypeText() {
        return lirTypeText;
    }

    @Override
    public @NotNull String getCStorageTypeName() {
        return cStorageTypeName;
    }

    @Override
    public @NotNull String getCInitHelperName() {
        return cInitHelperName;
    }

    @Override
    public @NotNull String getCDestroyHelperName() {
        return cDestroyHelperName;
    }

    @Override
    public @NotNull String getCCopyHelperName() {
        return cCopyHelperName;
    }

    @Override
    public boolean isDirectStructAssignmentSafe() {
        return false;
    }
}
