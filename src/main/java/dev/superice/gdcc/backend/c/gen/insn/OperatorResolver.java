package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared resolver for operator instruction codegen.
///
/// Stage 1 scope:
/// - Define path-decision entry points for unary/binary operators.
/// - Define metadata matching entry points.
/// - Define return-type resolution entry points.
///
/// Stage 2 extends this resolver with compare specializations.
public final class OperatorResolver {
    public enum OperatorPath {
        UNIMPLEMENTED,
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
        return PathDecision.unresolved("Unary operator path is not implemented yet");
    }

    public @NotNull PathDecision resolveBinaryPath(@NotNull CBodyBuilder bodyBuilder,
                                                   @NotNull GodotOperator op,
                                                   @NotNull GdType leftType,
                                                   @NotNull GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(op);
        Objects.requireNonNull(leftType);
        Objects.requireNonNull(rightType);

        if (!isComparisonOperator(op)) {
            return PathDecision.unresolved("Only compare specialization is implemented in current stage");
        }

        if (leftType instanceof GdNilType || rightType instanceof GdNilType) {
            if (!isEqualityOperator(op)) {
                return PathDecision.unresolved("Nil specialization currently supports only == and !=");
            }
            return new PathDecision(
                    OperatorPath.NIL_COMPARISON,
                    GdBoolType.BOOL,
                    "Nil compare specialization"
            );
        }

        if (leftType instanceof GdObjectType && rightType instanceof GdObjectType) {
            if (!isEqualityOperator(op)) {
                return PathDecision.unresolved("Object comparison supports only == and !=");
            }
            return new PathDecision(
                    OperatorPath.OBJECT_COMPARISON,
                    GdBoolType.BOOL,
                    "Object compare specialization"
            );
        }

        if (leftType instanceof GdPrimitiveType && rightType instanceof GdPrimitiveType) {
            if (!matchesBinaryMetadata(bodyBuilder, leftType, op, rightType)) {
                return PathDecision.unresolved("Primitive compare metadata is missing");
            }
            var metadataReturnType = resolveOperatorReturnType(bodyBuilder, leftType, op, rightType);
            if (!(metadataReturnType instanceof GdBoolType)) {
                return PathDecision.unresolved("Primitive compare metadata return type must be bool");
            }
            return new PathDecision(
                    OperatorPath.PRIMITIVE_COMPARISON,
                    GdBoolType.BOOL,
                    "Primitive compare specialization"
            );
        }

        return PathDecision.unresolved("No compare specialization matched for operand types");
    }

    public boolean matchesUnaryMetadata(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull GdType operandType,
                                        @NotNull GodotOperator op) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(operandType);
        Objects.requireNonNull(op);
        var builtinClass = findBuiltinClass(bodyBuilder.classRegistry(), operandType);
        if (builtinClass == null) {
            return false;
        }
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null) {
                continue;
            }
            var classOperatorEnum = parseOperator(classOperator);
            if (classOperatorEnum != op) {
                continue;
            }
            if (normalizeTypeName(classOperator.rightType()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesBinaryMetadata(@NotNull CBodyBuilder bodyBuilder,
                                         @NotNull GdType leftType,
                                         @NotNull GodotOperator op,
                                         @NotNull GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(leftType);
        Objects.requireNonNull(op);
        Objects.requireNonNull(rightType);
        var builtinClass = findBuiltinClass(bodyBuilder.classRegistry(), leftType);
        if (builtinClass == null) {
            return false;
        }
        var normalizedRightType = normalizeTypeName(rightType.getTypeName());
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null) {
                continue;
            }
            var classOperatorEnum = parseOperator(classOperator);
            if (classOperatorEnum != op) {
                continue;
            }
            if (normalizedRightType.equals(normalizeTypeName(classOperator.rightType()))) {
                return true;
            }
        }
        return false;
    }

    public @Nullable GdType resolveOperatorReturnType(@NotNull CBodyBuilder bodyBuilder,
                                                      @NotNull GdType leftType,
                                                      @NotNull GodotOperator op,
                                                      @Nullable GdType rightType) {
        Objects.requireNonNull(bodyBuilder);
        Objects.requireNonNull(leftType);
        Objects.requireNonNull(op);

        var builtinClass = findBuiltinClass(bodyBuilder.classRegistry(), leftType);
        if (builtinClass == null) {
            return null;
        }

        var normalizedRightType = rightType == null ? "" : normalizeTypeName(rightType.getTypeName());
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null) {
                continue;
            }
            var classOperatorEnum = parseOperator(classOperator);
            if (classOperatorEnum != op) {
                continue;
            }
            if (!normalizedRightType.equals(normalizeTypeName(classOperator.rightType()))) {
                continue;
            }
            var returnTypeName = classOperator.returnType();
            if (returnTypeName == null || returnTypeName.isBlank()) {
                return null;
            }
            return ClassRegistry.tryParseTextType(returnTypeName);
        }
        return null;
    }

    private @Nullable GodotOperator parseOperator(@NotNull ExtensionBuiltinClass.ClassOperator classOperator) {
        try {
            return classOperator.operator();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private @Nullable ExtensionBuiltinClass findBuiltinClass(@NotNull ClassRegistry classRegistry,
                                                             @NotNull GdType type) {
        var typeName = type.getTypeName();
        return classRegistry.findBuiltinClass(typeName);
    }

    private @NotNull String normalizeTypeName(@Nullable String typeName) {
        if (typeName == null) {
            return "";
        }
        return typeName.trim();
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
}
