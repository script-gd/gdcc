package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Published expression-typing fact stored in `FrontendAnalysisData.expressionTypes()`.
///
/// `publishedType` means “the type downstream consumers may see for this expression”. It is not
/// synonymous with “exact static success”:
/// - `RESOLVED` publishes an exact type.
/// - `BLOCKED` may still carry the blocked winner type.
/// - `DYNAMIC` publishes widened `Variant` while preserving dynamic provenance in `status`.
public record FrontendExpressionType(
        @NotNull FrontendExpressionTypeStatus status,
        @Nullable GdType publishedType,
        @Nullable String detailReason
) {
    public FrontendExpressionType {
        Objects.requireNonNull(status, "status must not be null");
        switch (status) {
            case RESOLVED -> {
                Objects.requireNonNull(publishedType, "publishedType must not be null for resolved expression types");
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved expression types");
                }
            }
            case BLOCKED -> requireNonBlank(detailReason, "detailReason");
            case DYNAMIC -> {
                Objects.requireNonNull(publishedType, "publishedType must not be null for dynamic expression types");
                requireNonBlank(detailReason, "detailReason");
                if (!(publishedType instanceof GdVariantType)) {
                    throw new IllegalArgumentException("dynamic expression types must publish Variant");
                }
            }
            case DEFERRED, FAILED, UNSUPPORTED -> {
                if (publishedType != null) {
                    throw new IllegalArgumentException(
                            "publishedType must be null for %s expression types".formatted(status)
                    );
                }
                requireNonBlank(detailReason, "detailReason");
            }
        }
    }

    public static @NotNull FrontendExpressionType resolved(@NotNull GdType publishedType) {
        return new FrontendExpressionType(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(publishedType, "publishedType must not be null"),
                null
        );
    }

    public static @NotNull FrontendExpressionType blocked(
            @Nullable GdType publishedType,
            @NotNull String detailReason
    ) {
        return new FrontendExpressionType(FrontendExpressionTypeStatus.BLOCKED, publishedType, detailReason);
    }

    public static @NotNull FrontendExpressionType deferred(@NotNull String detailReason) {
        return new FrontendExpressionType(FrontendExpressionTypeStatus.DEFERRED, null, detailReason);
    }

    public static @NotNull FrontendExpressionType dynamic(@NotNull String detailReason) {
        return new FrontendExpressionType(FrontendExpressionTypeStatus.DYNAMIC, GdVariantType.VARIANT, detailReason);
    }

    public static @NotNull FrontendExpressionType failed(@NotNull String detailReason) {
        return new FrontendExpressionType(FrontendExpressionTypeStatus.FAILED, null, detailReason);
    }

    public static @NotNull FrontendExpressionType unsupported(@NotNull String detailReason) {
        return new FrontendExpressionType(FrontendExpressionTypeStatus.UNSUPPORTED, null, detailReason);
    }

    private static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
