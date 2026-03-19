package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Published call-resolution fact for one body-phase chain step.
///
/// The model keeps route kind and result status separate so downstream consumers can distinguish
/// constructor/static/dynamic paths without flattening everything into one generic "call hit".
public record FrontendResolvedCall(
        @NotNull String callableName,
        @NotNull FrontendCallResolutionKind callKind,
        @NotNull FrontendCallResolutionStatus status,
        @NotNull FrontendReceiverKind receiverKind,
        @Nullable ScopeOwnerKind ownerKind,
        @Nullable GdType receiverType,
        @Nullable GdType returnType,
        @NotNull List<GdType> argumentTypes,
        @Nullable Object declarationSite,
        @Nullable String detailReason
) {
    public FrontendResolvedCall {
        callableName = requireNonBlank(callableName, "callableName");
        Objects.requireNonNull(callKind, "callKind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receiverKind, "receiverKind must not be null");
        argumentTypes = copyArgumentTypes(argumentTypes);
        validateState(callKind, status, receiverKind, returnType, detailReason);
    }

    public static @NotNull FrontendResolvedCall resolved(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType returnType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.RESOLVED,
                receiverKind,
                ownerKind,
                receiverType,
                Objects.requireNonNull(returnType, "returnType must not be null"),
                argumentTypes,
                declarationSite,
                null
        );
    }

    public static @NotNull FrontendResolvedCall blocked(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType returnType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.BLOCKED,
                receiverKind,
                ownerKind,
                receiverType,
                Objects.requireNonNull(returnType, "returnType must not be null"),
                argumentTypes,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall deferred(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.DEFERRED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall dynamic(
            @NotNull String callableName,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                FrontendCallResolutionKind.DYNAMIC_FALLBACK,
                FrontendCallResolutionStatus.DYNAMIC,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall failed(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.FAILED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall unsupported(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.UNSUPPORTED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                declarationSite,
                detailReason
        );
    }

    private static void validateState(
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendCallResolutionStatus status,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType returnType,
            @Nullable String detailReason
    ) {
        if (status == FrontendCallResolutionStatus.RESOLVED
                || status == FrontendCallResolutionStatus.BLOCKED) {
            if (callKind == FrontendCallResolutionKind.UNKNOWN
                    || callKind == FrontendCallResolutionKind.DYNAMIC_FALLBACK) {
                throw new IllegalArgumentException("callKind must be a concrete route for resolved/blocked call results");
            }
            if (receiverKind == FrontendReceiverKind.UNKNOWN) {
                throw new IllegalArgumentException("receiverKind must not be UNKNOWN for resolved/blocked call results");
            }
            Objects.requireNonNull(returnType, "returnType must not be null for resolved/blocked call results");
            if (status == FrontendCallResolutionStatus.RESOLVED && detailReason != null) {
                throw new IllegalArgumentException("detailReason must be null for RESOLVED call results");
            }
            if (status == FrontendCallResolutionStatus.BLOCKED) {
                requireNonBlank(detailReason, "detailReason");
            }
            return;
        }

        if (returnType != null) {
            throw new IllegalArgumentException("returnType must be null for non-success call results");
        }
        requireNonBlank(detailReason, "detailReason");
        if (status == FrontendCallResolutionStatus.DYNAMIC) {
            if (callKind != FrontendCallResolutionKind.DYNAMIC_FALLBACK) {
                throw new IllegalArgumentException("DYNAMIC call results must use DYNAMIC_FALLBACK callKind");
            }
            if (receiverKind == FrontendReceiverKind.UNKNOWN) {
                throw new IllegalArgumentException("receiverKind must not be UNKNOWN for DYNAMIC call results");
            }
            return;
        }
        if (callKind == FrontendCallResolutionKind.DYNAMIC_FALLBACK) {
            throw new IllegalArgumentException("DYNAMIC_FALLBACK callKind is only valid for DYNAMIC call results");
        }
    }

    private static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static @NotNull List<GdType> copyArgumentTypes(@Nullable List<GdType> argumentTypes) {
        var source = Objects.requireNonNull(argumentTypes, "argumentTypes must not be null");
        var copied = new java.util.ArrayList<GdType>(source.size());
        for (var argumentType : source) {
            if (argumentType == null) {
                throw new IllegalArgumentException("argumentTypes must not contain null elements");
            }
            copied.add(argumentType);
        }
        return List.copyOf(copied);
    }
}
