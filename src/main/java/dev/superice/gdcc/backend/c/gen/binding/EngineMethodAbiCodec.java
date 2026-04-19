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
import dev.superice.gdcc.type.GdPackedArrayType;
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
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Objects;

/// Canonical ABI descriptor codec for exact-engine helper identity.
/// Leaf builtins use frozen one-letter tags, while recursive object/container forms stay fully reversible.
public final class EngineMethodAbiCodec {
    private static final char OBJECT_PREFIX = 'L';
    private static final char ARRAY_PREFIX = 'A';
    private static final char DICTIONARY_PREFIX = 'D';

    private EngineMethodAbiCodec() {
    }

    public static @NotNull String generate(@NotNull EngineMethodAbiSignature signature) {
        Objects.requireNonNull(signature);
        var builder = new StringBuilder("P");
        for (var parameterType : signature.parameterTypes()) {
            appendTypeDescriptor(builder, parameterType, false);
        }
        builder.append("_R");
        appendTypeDescriptor(builder, signature.returnType(), true);
        if (signature.vararg()) {
            builder.append("_Xv");
        }
        return builder.toString();
    }

    public static @NotNull EngineMethodAbiSignature parse(@NotNull String descriptor) {
        Objects.requireNonNull(descriptor);
        if (!descriptor.startsWith("P")) {
            throw invalidDescriptor(descriptor, "descriptor must start with 'P'");
        }
        var parameterTypes = new ArrayList<GdType>();
        var index = 1;
        while (true) {
            if (index >= descriptor.length()) {
                throw invalidDescriptor(descriptor, "missing return separator '_R'");
            }
            if (descriptor.startsWith("_R", index)) {
                index += 2;
                break;
            }
            var parameter = parseTypeDescriptor(descriptor, index, false);
            parameterTypes.add(parameter.type());
            index = parameter.nextIndex();
        }
        var returnType = parseTypeDescriptor(descriptor, index, true);
        index = returnType.nextIndex();
        if (index == descriptor.length()) {
            return new EngineMethodAbiSignature(parameterTypes, returnType.type(), false);
        }
        if (descriptor.startsWith("_Xv", index) && index + 3 == descriptor.length()) {
            return new EngineMethodAbiSignature(parameterTypes, returnType.type(), true);
        }
        throw invalidDescriptor(descriptor, "trailing content after return descriptor");
    }

    private static void appendTypeDescriptor(@NotNull StringBuilder builder,
                                             @NotNull GdType type,
                                             boolean allowVoid) {
        Objects.requireNonNull(type);
        switch (type) {
            case GdVoidType _ -> {
                if (!allowVoid) {
                    throw new IllegalArgumentException("Void is not a valid engine helper parameter type");
                }
                builder.append('V');
            }
            case GdVariantType _ -> builder.append('R');
            case GdNilType _ -> builder.append('n');
            case GdObjectType objectType -> appendObjectDescriptor(builder, objectType);
            case GdArrayType arrayType -> {
                builder.append(ARRAY_PREFIX);
                appendTypeDescriptor(builder, arrayType.getValueType(), false);
                builder.append('_');
            }
            case GdDictionaryType dictionaryType -> {
                builder.append(DICTIONARY_PREFIX);
                appendTypeDescriptor(builder, dictionaryType.getKeyType(), false);
                appendTypeDescriptor(builder, dictionaryType.getValueType(), false);
                builder.append('_');
            }
            default -> builder.append(simpleDescriptorCode(type));
        }
    }

    private static void appendObjectDescriptor(@NotNull StringBuilder builder, @NotNull GdObjectType objectType) {
        var className = objectType.getTypeName();
        if (className.isBlank()) {
            throw new IllegalArgumentException("Engine helper object type must have a class name");
        }
        builder.append(OBJECT_PREFIX).append(className.length()).append(className).append('_');
    }

    private static @NotNull ParsedType parseTypeDescriptor(@NotNull String descriptor, int index, boolean allowVoid) {
        if (index >= descriptor.length()) {
            throw invalidDescriptor(descriptor, "unexpected end of descriptor");
        }
        return switch (descriptor.charAt(index)) {
            case 'V' -> {
                if (!allowVoid) {
                    throw invalidDescriptor(descriptor, "void is only valid as a return descriptor");
                }
                yield new ParsedType(GdVoidType.VOID, index + 1);
            }
            case 'R' -> new ParsedType(GdVariantType.VARIANT, index + 1);
            case 'n' -> new ParsedType(GdNilType.NIL, index + 1);
            case OBJECT_PREFIX -> parseObjectDescriptor(descriptor, index);
            case ARRAY_PREFIX -> parseArrayDescriptor(descriptor, index);
            case DICTIONARY_PREFIX -> parseDictionaryDescriptor(descriptor, index);
            default -> new ParsedType(simpleTypeForCode(descriptor.charAt(index)), index + 1);
        };
    }

    private static @NotNull ParsedType parseObjectDescriptor(@NotNull String descriptor, int index) {
        var lengthStart = index + 1;
        var cursor = lengthStart;
        while (cursor < descriptor.length() && Character.isDigit(descriptor.charAt(cursor))) {
            cursor++;
        }
        if (cursor == lengthStart) {
            throw invalidDescriptor(descriptor, "object descriptor is missing class-name length");
        }
        var classNameLength = parseLength(descriptor, lengthStart, cursor);
        if (classNameLength <= 0) {
            throw invalidDescriptor(descriptor, "object descriptor class-name length must be positive");
        }
        if (cursor + classNameLength >= descriptor.length()) {
            throw invalidDescriptor(descriptor, "object descriptor is truncated");
        }
        var className = descriptor.substring(cursor, cursor + classNameLength);
        var end = cursor + classNameLength;
        if (descriptor.charAt(end) != '_') {
            throw invalidDescriptor(descriptor, "object descriptor is missing trailing '_'");
        }
        return new ParsedType(new GdObjectType(className), end + 1);
    }

    private static int parseLength(@NotNull String descriptor, int start, int end) {
        try {
            return Integer.parseInt(descriptor.substring(start, end));
        } catch (NumberFormatException ex) {
            throw invalidDescriptor(descriptor, "invalid object descriptor class-name length");
        }
    }

    private static @NotNull ParsedType parseArrayDescriptor(@NotNull String descriptor, int index) {
        var element = parseTypeDescriptor(descriptor, index + 1, false);
        if (element.nextIndex() >= descriptor.length() || descriptor.charAt(element.nextIndex()) != '_') {
            throw invalidDescriptor(descriptor, "array descriptor is missing trailing '_'");
        }
        return new ParsedType(new GdArrayType(element.type()), element.nextIndex() + 1);
    }

    private static @NotNull ParsedType parseDictionaryDescriptor(@NotNull String descriptor, int index) {
        var key = parseTypeDescriptor(descriptor, index + 1, false);
        var value = parseTypeDescriptor(descriptor, key.nextIndex(), false);
        if (value.nextIndex() >= descriptor.length() || descriptor.charAt(value.nextIndex()) != '_') {
            throw invalidDescriptor(descriptor, "dictionary descriptor is missing trailing '_'");
        }
        return new ParsedType(new GdDictionaryType(key.type(), value.type()), value.nextIndex() + 1);
    }

    private static char simpleDescriptorCode(@NotNull GdType type) {
        return switch (type) {
            case GdBoolType _ -> 'Z';
            case GdIntType _ -> 'I';
            case GdFloatType _ -> 'F';
            case GdStringType _ -> 'T';
            case GdFloatVectorType vectorType when vectorType.getDimension() == 2 -> 'J';
            case GdIntVectorType vectorType when vectorType.getDimension() == 2 -> 'j';
            case GdRect2Type _ -> 'E';
            case GdRect2iType _ -> 'e';
            case GdFloatVectorType vectorType when vectorType.getDimension() == 3 -> 'K';
            case GdIntVectorType vectorType when vectorType.getDimension() == 3 -> 'k';
            case GdTransform2DType _ -> 'X';
            case GdFloatVectorType vectorType when vectorType.getDimension() == 4 -> 'M';
            case GdIntVectorType vectorType when vectorType.getDimension() == 4 -> 'm';
            case GdPlaneType _ -> 'P';
            case GdQuaternionType _ -> 'Q';
            case GdAABBType _ -> 'H';
            case GdBasisType _ -> 'B';
            case GdTransform3DType _ -> 'W';
            case GdProjectionType _ -> 'U';
            case GdColorType _ -> 'O';
            case GdStringNameType _ -> 'S';
            case GdNodePathType _ -> 'N';
            case GdRidType _ -> 'Y';
            case GdCallableType _ -> 'C';
            case GdSignalType _ -> 'G';
            case GdPackedNumericArrayType packedType when packedType.equals(GdPackedNumericArrayType.PACKED_BYTE_ARRAY) ->
                    'y';
            case GdPackedNumericArrayType packedType when packedType.equals(GdPackedNumericArrayType.PACKED_INT32_ARRAY) ->
                    'i';
            case GdPackedNumericArrayType packedType when packedType.equals(GdPackedNumericArrayType.PACKED_INT64_ARRAY) ->
                    'l';
            case GdPackedNumericArrayType packedType when packedType.equals(GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY) ->
                    'f';
            case GdPackedNumericArrayType packedType when packedType.equals(GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY) ->
                    'd';
            case GdPackedStringArrayType _ -> 's';
            case GdPackedVectorArrayType packedType when packedType.equals(GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY) ->
                    'u';
            case GdPackedVectorArrayType packedType when packedType.equals(GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY) ->
                    'v';
            case GdPackedVectorArrayType packedType when packedType.equals(GdPackedVectorArrayType.PACKED_COLOR_ARRAY) ->
                    'o';
            case GdPackedVectorArrayType packedType when packedType.equals(GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY) ->
                    'x';
            case GdPackedArrayType _ -> throw new IllegalArgumentException(
                    "Unsupported packed-array subtype in engine method ABI codec: " + type.getTypeName()
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported engine method ABI type: " + type.getTypeName()
            );
        };
    }

    private static @NotNull GdType simpleTypeForCode(char code) {
        return switch (code) {
            case 'Z' -> GdBoolType.BOOL;
            case 'I' -> GdIntType.INT;
            case 'F' -> GdFloatType.FLOAT;
            case 'T' -> GdStringType.STRING;
            case 'J' -> GdFloatVectorType.VECTOR2;
            case 'j' -> GdIntVectorType.VECTOR2I;
            case 'E' -> GdRect2Type.RECT2;
            case 'e' -> GdRect2iType.RECT2I;
            case 'K' -> GdFloatVectorType.VECTOR3;
            case 'k' -> GdIntVectorType.VECTOR3I;
            case 'X' -> GdTransform2DType.TRANSFORM2D;
            case 'M' -> GdFloatVectorType.VECTOR4;
            case 'm' -> GdIntVectorType.VECTOR4I;
            case 'P' -> GdPlaneType.PLANE;
            case 'Q' -> GdQuaternionType.QUATERNION;
            case 'H' -> GdAABBType.AABB;
            case 'B' -> GdBasisType.BASIS;
            case 'W' -> GdTransform3DType.TRANSFORM3D;
            case 'U' -> GdProjectionType.PROJECTION;
            case 'O' -> GdColorType.COLOR;
            case 'S' -> GdStringNameType.STRING_NAME;
            case 'N' -> GdNodePathType.NODE_PATH;
            case 'Y' -> GdRidType.RID;
            case 'C' -> new GdCallableType();
            case 'G' -> new GdSignalType();
            case 'y' -> GdPackedNumericArrayType.PACKED_BYTE_ARRAY;
            case 'i' -> GdPackedNumericArrayType.PACKED_INT32_ARRAY;
            case 'l' -> GdPackedNumericArrayType.PACKED_INT64_ARRAY;
            case 'f' -> GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY;
            case 'd' -> GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY;
            case 's' -> GdPackedStringArrayType.PACKED_STRING_ARRAY;
            case 'u' -> GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY;
            case 'v' -> GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY;
            case 'o' -> GdPackedVectorArrayType.PACKED_COLOR_ARRAY;
            case 'x' -> GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY;
            default -> throw new IllegalArgumentException("Unknown engine method ABI type code: " + code);
        };
    }

    private static @NotNull IllegalArgumentException invalidDescriptor(
            @NotNull String descriptor,
            @NotNull String reason
    ) {
        return new IllegalArgumentException("Invalid engine method ABI descriptor '" + descriptor + "': " + reason);
    }

    private record ParsedType(@NotNull GdType type, int nextIndex) {
    }
}
