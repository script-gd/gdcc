package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// Shared resolver for operator instruction codegen.
///
/// Stage 1 scope:
/// - Define path-decision entry points for unary/binary operators.
/// - Define metadata matching entry points.
/// - Define return-type resolution entry points.
///
/// Stage 2 extends this resolver with compare specializations.
public final class OperatorResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorResolver.class);
    private static final Pattern NON_IDENTIFIER_PATTERN = Pattern.compile("[^a-z0-9_]");

    public enum OperatorPath {
        UNIMPLEMENTED,
        BUILTIN_EVALUATOR,
        VARIANT_EVALUATE,
        PRIMITIVE_FAST_PATH,
        PRIMITIVE_COMPARISON,
        OBJECT_COMPARISON,
        NIL_COMPARISON
    }

    public record PathDecision(@NotNull OperatorPath path,
                               @Nullable GdType semanticResultType,
                               @NotNull String reason) {
        public PathDecision {
            Objects.requireNonNull(path);
            Objects.requireNonNull(reason);
        }

        public static @NotNull PathDecision unresolved(@NotNull String reason) {
            return new PathDecision(OperatorPath.UNIMPLEMENTED, null, reason);
        }
    }

    public @NotNull PathDecision resolveUnaryPath(@NotNull CBodyBuilder bodyBuilder,
                                                  @NotNull GodotOperator op,
                                                  @NotNull GdType operandType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(op);
        Objects.requireNonNull(operandType);

        var semanticResultType = resolveOperatorReturnType(bodyBuilder, operandType, op, null);
        if (semanticResultType == null) {
            return PathDecision.unresolved(
                    "Unary operator metadata is missing for signature (" +
                            operandType.getTypeName() + ", " + op.name() + ", \"\")"
            );
        }

        resolveVariantTypeEnumLiteral(bodyBuilder, operandType);
        resolveVariantTypeEnumLiteral(bodyBuilder, semanticResultType);

        return new PathDecision(
                OperatorPath.BUILTIN_EVALUATOR,
                semanticResultType,
                "Unary builtin evaluator path"
        );
    }

    public @NotNull PathDecision resolveBinaryPath(@NotNull CBodyBuilder bodyBuilder,
                                                   @NotNull GodotOperator op,
                                                   @NotNull GdType leftType,
                                                   @NotNull GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(op);
        Objects.requireNonNull(leftType);
        Objects.requireNonNull(rightType);

        if (isVariantOperand(leftType, rightType)) {
            return new PathDecision(
                    OperatorPath.VARIANT_EVALUATE,
                    GdVariantType.VARIANT,
                    "Variant dynamic evaluate path"
            );
        }

        if (isComparisonOperator(op)) {
            if (leftType instanceof GdNilType || rightType instanceof GdNilType) {
                if (!isEqualityOperator(op)) {
                    return PathDecision.unresolved(
                            withBinarySignature("Nil specialization currently supports only == and !=",
                                    leftType, op, rightType)
                    );
                }
                return new PathDecision(
                        OperatorPath.NIL_COMPARISON,
                        GdBoolType.BOOL,
                        "Nil compare specialization"
                );
            }

            if (leftType instanceof GdObjectType && rightType instanceof GdObjectType) {
                if (!isEqualityOperator(op)) {
                    return PathDecision.unresolved(
                            withBinarySignature("Object comparison supports only == and !=",
                                    leftType, op, rightType)
                    );
                }
                return new PathDecision(
                        OperatorPath.OBJECT_COMPARISON,
                        GdBoolType.BOOL,
                        "Object compare specialization"
                );
            }

            if (leftType instanceof GdPrimitiveType && rightType instanceof GdPrimitiveType) {
                if (!matchesBinaryMetadata(bodyBuilder, leftType, op, rightType)) {
                    return PathDecision.unresolved(
                            withBinarySignature("Primitive compare metadata is missing",
                                    leftType, op, rightType)
                    );
                }
                var metadataReturnType = resolveOperatorReturnType(bodyBuilder, leftType, op, rightType);
                if (!(metadataReturnType instanceof GdBoolType)) {
                    var resolvedType = metadataReturnType == null ? "<null>" : metadataReturnType.getTypeName();
                    return PathDecision.unresolved(
                            withBinarySignature(
                                    "Primitive compare metadata return type must be bool, but got '" + resolvedType + "'",
                                    leftType,
                                    op,
                                    rightType
                            )
                    );
                }
                return new PathDecision(
                        OperatorPath.PRIMITIVE_COMPARISON,
                        GdBoolType.BOOL,
                        "Primitive compare specialization"
                );
            }
        }

        if (leftType instanceof GdPrimitiveType leftPrimitiveType &&
                rightType instanceof GdPrimitiveType rightPrimitiveType &&
                isPrimitiveFastPathWhitelisted(leftPrimitiveType, op, rightPrimitiveType)) {
            var metadataReturnType = resolveOperatorReturnType(bodyBuilder, leftType, op, rightType);
            if (metadataReturnType == null) {
                return PathDecision.unresolved(
                        "Primitive fast path metadata is missing for signature (" +
                                leftType.getTypeName() + ", " + op.name() + ", " + rightType.getTypeName() + ")"
                );
            }
            return new PathDecision(
                    OperatorPath.PRIMITIVE_FAST_PATH,
                    metadataReturnType,
                    "Primitive fast path"
            );
        }

        var semanticResultType = resolveOperatorReturnType(bodyBuilder, leftType, op, rightType);
        if (semanticResultType == null) {
            return PathDecision.unresolved(
                    "Binary operator metadata is missing for signature (" +
                            leftType.getTypeName() + ", " + op.name() + ", " + rightType.getTypeName() + ")"
            );
        }

        resolveVariantTypeEnumLiteral(bodyBuilder, leftType);
        resolveVariantTypeEnumLiteral(bodyBuilder, rightType);
        resolveVariantTypeEnumLiteral(bodyBuilder, semanticResultType);

        return new PathDecision(
                OperatorPath.BUILTIN_EVALUATOR,
                semanticResultType,
                "Binary builtin evaluator path"
        );
    }

    public boolean matchesUnaryMetadata(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull GdType operandType,
                                        @NotNull GodotOperator op) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(operandType);
        Objects.requireNonNull(op);
        return resolveUnaryMetadataReturnType(bodyBuilder, operandType, op) != null;
    }

    public boolean matchesBinaryMetadata(@NotNull CBodyBuilder bodyBuilder,
                                         @NotNull GdType leftType,
                                         @NotNull GodotOperator op,
                                         @NotNull GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(leftType);
        Objects.requireNonNull(op);
        Objects.requireNonNull(rightType);
        return resolveBinaryMetadataReturnType(bodyBuilder, leftType, op, rightType) != null;
    }

    public @Nullable GdType resolveOperatorReturnType(@NotNull CBodyBuilder bodyBuilder,
                                                      @NotNull GdType leftType,
                                                      @NotNull GodotOperator op,
                                                      @Nullable GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(leftType);
        Objects.requireNonNull(op);
        if (rightType == null) {
            return resolveUnaryMetadataReturnType(bodyBuilder, leftType, op);
        }
        return resolveBinaryMetadataReturnType(bodyBuilder, leftType, op, rightType);
    }

    public @NotNull String resolveVariantOperatorEnumLiteral(@NotNull GodotOperator op) {
        return switch (op) {
            case EQUAL -> "GDEXTENSION_VARIANT_OP_EQUAL";
            case NOT_EQUAL -> "GDEXTENSION_VARIANT_OP_NOT_EQUAL";
            case LESS -> "GDEXTENSION_VARIANT_OP_LESS";
            case LESS_EQUAL -> "GDEXTENSION_VARIANT_OP_LESS_EQUAL";
            case GREATER -> "GDEXTENSION_VARIANT_OP_GREATER";
            case GREATER_EQUAL -> "GDEXTENSION_VARIANT_OP_GREATER_EQUAL";
            case ADD -> "GDEXTENSION_VARIANT_OP_ADD";
            case SUBTRACT -> "GDEXTENSION_VARIANT_OP_SUBTRACT";
            case MULTIPLY -> "GDEXTENSION_VARIANT_OP_MULTIPLY";
            case DIVIDE -> "GDEXTENSION_VARIANT_OP_DIVIDE";
            case NEGATE -> "GDEXTENSION_VARIANT_OP_NEGATE";
            case POSITIVE -> "GDEXTENSION_VARIANT_OP_POSITIVE";
            case MODULE -> "GDEXTENSION_VARIANT_OP_MODULE";
            case POWER -> "GDEXTENSION_VARIANT_OP_POWER";
            case SHIFT_LEFT -> "GDEXTENSION_VARIANT_OP_SHIFT_LEFT";
            case SHIFT_RIGHT -> "GDEXTENSION_VARIANT_OP_SHIFT_RIGHT";
            case BIT_AND -> "GDEXTENSION_VARIANT_OP_BIT_AND";
            case BIT_OR -> "GDEXTENSION_VARIANT_OP_BIT_OR";
            case BIT_XOR -> "GDEXTENSION_VARIANT_OP_BIT_XOR";
            case BIT_NOT -> "GDEXTENSION_VARIANT_OP_BIT_NEGATE";
            case AND -> "GDEXTENSION_VARIANT_OP_AND";
            case OR -> "GDEXTENSION_VARIANT_OP_OR";
            case XOR -> "GDEXTENSION_VARIANT_OP_XOR";
            case NOT -> "GDEXTENSION_VARIANT_OP_NOT";
            case IN -> "GDEXTENSION_VARIANT_OP_IN";
        };
    }

    public @NotNull String resolveVariantTypeEnumLiteral(@NotNull CBodyBuilder bodyBuilder,
                                                         @NotNull GdType type) {
        var extensionType = type.getGdExtensionType();
        if (extensionType == null) {
            throw bodyBuilder.invalidInsn(
                    "Cannot map type '" + type.getTypeName() + "' to GDExtensionVariantType"
            );
        }
        return "GDEXTENSION_VARIANT_TYPE_" + extensionType.name();
    }

    public @NotNull String renderUnaryEvaluatorHelperName(@NotNull GodotOperator op,
                                                          @NotNull GdType operandType,
                                                          @NotNull GdType returnType) {
        return "gdcc_eval_unary_" + sanitizeIdentifier(op.name()) + "_" +
                sanitizeIdentifier(operandType.getTypeName()) +
                "_to_" + sanitizeIdentifier(returnType.getTypeName());
    }

    public @NotNull String renderBinaryEvaluatorHelperName(@NotNull GodotOperator op,
                                                           @NotNull GdType leftType,
                                                           @NotNull GdType rightType,
                                                           @NotNull GdType returnType) {
        return "gdcc_eval_binary_" + sanitizeIdentifier(op.name()) + "_" +
                sanitizeIdentifier(leftType.getTypeName()) + "_" +
                sanitizeIdentifier(rightType.getTypeName()) +
                "_to_" + sanitizeIdentifier(returnType.getTypeName());
    }

    private @Nullable GdType resolveUnaryMetadataReturnType(@NotNull CBodyBuilder bodyBuilder,
                                                            @NotNull GdType operandType,
                                                            @NotNull GodotOperator op) {
        var builtinClass = findBuiltinClass(bodyBuilder.classRegistry(), operandType);
        if (builtinClass == null) {
            return null;
        }
        for (var i = 0; i < builtinClass.operators().size(); i++) {
            var classOperator = builtinClass.operators().get(i);
            if (classOperator == null) {
                continue;
            }
            var metadataOp = parseOperatorSafely(bodyBuilder, builtinClass, classOperator, i);
            if (metadataOp != op) {
                continue;
            }
            if (!normalizeTypeName(classOperator.rightType()).isEmpty()) {
                continue;
            }
            var parsedReturnType = parseReturnTypeSafely(bodyBuilder, builtinClass, classOperator, i);
            if (parsedReturnType == null) {
                continue;
            }
            return parsedReturnType;
        }
        return null;
    }

    private @Nullable GdType resolveBinaryMetadataReturnType(@NotNull CBodyBuilder bodyBuilder,
                                                             @NotNull GdType leftType,
                                                             @NotNull GodotOperator op,
                                                             @NotNull GdType rightType) {
        var builtinClass = findBuiltinClass(bodyBuilder.classRegistry(), leftType);
        if (builtinClass == null) {
            return null;
        }
        var normalizedRightType = normalizeTypeName(rightType.getTypeName());
        for (var i = 0; i < builtinClass.operators().size(); i++) {
            var classOperator = builtinClass.operators().get(i);
            if (classOperator == null) {
                continue;
            }
            var metadataOp = parseOperatorSafely(bodyBuilder, builtinClass, classOperator, i);
            if (metadataOp != op) {
                continue;
            }
            var metadataRightType = normalizeTypeName(classOperator.rightType());
            if (metadataRightType.isEmpty()) {
                warnMetadataEntry(
                        bodyBuilder,
                        builtinClass,
                        classOperator,
                        i,
                        "binary operator metadata has empty right_type"
                );
                continue;
            }
            if (!normalizedRightType.equals(metadataRightType)) {
                continue;
            }
            var parsedReturnType = parseReturnTypeSafely(bodyBuilder, builtinClass, classOperator, i);
            if (parsedReturnType == null) {
                continue;
            }
            return parsedReturnType;
        }
        return null;
    }

    private @Nullable GodotOperator parseOperatorSafely(@NotNull CBodyBuilder bodyBuilder,
                                                        @NotNull ExtensionBuiltinClass builtinClass,
                                                        @NotNull ExtensionBuiltinClass.ClassOperator classOperator,
                                                        int operatorIndex) {
        try {
            return classOperator.operator();
        } catch (RuntimeException ex) {
            warnMetadataEntry(
                    bodyBuilder,
                    builtinClass,
                    classOperator,
                    operatorIndex,
                    "cannot parse metadata operator: " + ex.getMessage()
            );
            return null;
        }
    }

    private @Nullable GdType parseReturnTypeSafely(@NotNull CBodyBuilder bodyBuilder,
                                                   @NotNull ExtensionBuiltinClass builtinClass,
                                                   @NotNull ExtensionBuiltinClass.ClassOperator classOperator,
                                                   int operatorIndex) {
        var returnTypeName = normalizeTypeName(classOperator.returnType());
        if (returnTypeName.isEmpty()) {
            warnMetadataEntry(
                    bodyBuilder,
                    builtinClass,
                    classOperator,
                    operatorIndex,
                    "operator metadata has empty return_type"
            );
            return null;
        }
        var parsedReturnType = bodyBuilder.classRegistry().tryResolveDeclaredType(returnTypeName);
        if (parsedReturnType == null) {
            warnMetadataEntry(
                    bodyBuilder,
                    builtinClass,
                    classOperator,
                    operatorIndex,
                    "operator metadata return_type '" + returnTypeName + "' cannot be parsed"
            );
            return null;
        }
        return parsedReturnType;
    }

    private @Nullable ExtensionBuiltinClass findBuiltinClass(@NotNull ClassRegistry classRegistry,
                                                             @NotNull GdType type) {
        var typeName = type.getTypeName();
        return classRegistry.findBuiltinClass(typeName);
    }

    private void warnMetadataEntry(@NotNull CBodyBuilder bodyBuilder,
                                   @NotNull ExtensionBuiltinClass builtinClass,
                                   @NotNull ExtensionBuiltinClass.ClassOperator classOperator,
                                   int operatorIndex,
                                   @NotNull String reason) {
        var block = bodyBuilder.currentBlock();
        var blockId = block != null ? block.id() : "<unknown>";
        LOGGER.warn(
                "Skip invalid operator metadata: builtin='{}', operatorIndex={}, operator='{}', rightType='{}', returnType='{}', reason='{}', function='{}', block='{}', insnIndex={}",
                builtinClass.name(),
                operatorIndex,
                classOperator.name(),
                classOperator.rightType(),
                classOperator.returnType(),
                reason,
                bodyBuilder.func().getName(),
                blockId,
                bodyBuilder.currentInsnIndex()
        );
    }

    private @NotNull String normalizeTypeName(@Nullable String typeName) {
        if (typeName == null) {
            return "";
        }
        return typeName.trim();
    }

    private @NotNull String sanitizeIdentifier(@NotNull String raw) {
        var normalized = raw.trim().toLowerCase(Locale.ROOT);
        var sanitized = NON_IDENTIFIER_PATTERN.matcher(normalized).replaceAll("_");
        sanitized = sanitized.replaceAll("_+", "_");
        if (sanitized.startsWith("_")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.endsWith("_")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isBlank()) {
            return "unknown";
        }
        return sanitized;
    }

    private boolean isVariantOperand(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdVariantType || rightType instanceof GdVariantType;
    }

    private boolean isPrimitiveFastPathWhitelisted(@NotNull GdPrimitiveType leftType,
                                                   @NotNull GodotOperator op,
                                                   @NotNull GdPrimitiveType rightType) {
        var leftKind = primitiveKind(leftType);
        var rightKind = primitiveKind(rightType);
        return switch (op) {
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULE, POWER -> isNumericKind(leftKind) && isNumericKind(rightKind);
            case SHIFT_LEFT, SHIFT_RIGHT, BIT_AND, BIT_OR, BIT_XOR ->
                    leftKind == PrimitiveKind.INT && rightKind == PrimitiveKind.INT;
            case AND, OR, XOR -> true;
            default -> false;
        };
    }

    private @NotNull PrimitiveKind primitiveKind(@NotNull GdPrimitiveType type) {
        return switch (type) {
            case GdBoolType _ -> PrimitiveKind.BOOL;
            case GdIntType _ -> PrimitiveKind.INT;
            case GdFloatType _ -> PrimitiveKind.FLOAT;
        };
    }

    private boolean isNumericKind(@NotNull PrimitiveKind kind) {
        return kind == PrimitiveKind.INT || kind == PrimitiveKind.FLOAT;
    }

    private boolean isComparisonOperator(@NotNull GodotOperator op) {
        return switch (op) {
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> true;
            default -> false;
        };
    }

    private boolean isEqualityOperator(@NotNull GodotOperator op) {
        return op == GodotOperator.EQUAL || op == GodotOperator.NOT_EQUAL;
    }

    private @NotNull String withBinarySignature(@NotNull String reason,
                                                @NotNull GdType leftType,
                                                @NotNull GodotOperator op,
                                                @NotNull GdType rightType) {
        return reason + " for signature (" +
                leftType.getTypeName() + ", " + op.name() + ", " + rightType.getTypeName() + ")";
    }

    private enum PrimitiveKind {
        BOOL,
        INT,
        FLOAT
    }
}
