package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdContainerType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Shared subscript/container typing helper used by both expression typing and chain reduction.
///
/// The current contract intentionally keeps the supported surface conservative:
/// - typed `Array`, `Dictionary`, and `Packed*Array` families
/// - `Variant` receiver as runtime-dynamic `Variant`
/// - keyed builtin metadata outside the container family as explicit `UNSUPPORTED`
/// - everything else as explicit `FAILED`
public final class FrontendSubscriptSemanticSupport {
    private final @NotNull ClassRegistry classRegistry;

    public FrontendSubscriptSemanticSupport(@NotNull ClassRegistry classRegistry) {
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
    }

    public @NotNull FrontendExpressionType resolveSubscriptType(
            @NotNull GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @NotNull String accessDescription
    ) {
        var receiver = Objects.requireNonNull(receiverType, "receiverType must not be null");
        var arguments = List.copyOf(argumentTypes);
        var description = requireNonBlank(accessDescription, "accessDescription");

        if (receiver instanceof GdVariantType) {
            return FrontendExpressionType.dynamic(
                    "Variant receiver routes " + description + " through runtime-dynamic semantics"
            );
        }
        if (arguments.size() != 1) {
            return FrontendExpressionType.unsupported(
                    description + " supports exactly one key/index argument in the current frontend contract, got "
                            + arguments.size()
            );
        }
        if (receiver instanceof GdContainerType containerType) {
            var keyType = containerType.getKeyType();
            var providedKeyType = arguments.getFirst();
            if (!classRegistry.checkAssignable(providedKeyType, keyType)) {
                return FrontendExpressionType.failed(
                        description + " key/index type '" + providedKeyType.getTypeName()
                                + "' is not assignable to expected '" + keyType.getTypeName()
                                + "' for receiver '" + receiver.getTypeName() + "'"
                );
            }
            return FrontendExpressionType.resolved(containerType.getValueType());
        }

        var builtinClass = classRegistry.findBuiltinClass(receiver.getTypeName());
        if (builtinClass != null && builtinClass.isKeyed()) {
            return FrontendExpressionType.unsupported(
                    "Receiver type '" + receiver.getTypeName()
                            + "' advertises keyed access metadata, but " + description
                            + " only supports container-family receivers in the current frontend contract"
            );
        }
        return FrontendExpressionType.failed(
                "Receiver type '" + receiver.getTypeName() + "' does not support " + description
        );
    }

    private static @NotNull String requireNonBlank(@NotNull String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
