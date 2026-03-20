package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Published member-resolution fact for one body-phase chain step.
///
/// The record keeps the published member result model explicit:
/// - `bindingKind` preserves the member category when the step did resolve to a concrete symbol kind
/// - `status` captures why the step is resolved/blocked/deferred/dynamic/failed/unsupported
/// - `receiverKind` and optional type payloads let downstream consumers reason about suffix propagation
public record FrontendResolvedMember(
        @NotNull String memberName,
        @NotNull FrontendBindingKind bindingKind,
        @NotNull FrontendMemberResolutionStatus status,
        @NotNull FrontendReceiverKind receiverKind,
        @Nullable ScopeOwnerKind ownerKind,
        @Nullable GdType receiverType,
        @Nullable GdType resultType,
        @Nullable Object declarationSite,
        @Nullable String detailReason
) {
    public FrontendResolvedMember {
        memberName = requireNonBlank(memberName, "memberName");
        Objects.requireNonNull(bindingKind, "bindingKind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receiverKind, "receiverKind must not be null");
        validateState(bindingKind, status, receiverKind, resultType, detailReason);
    }

    public static @NotNull FrontendResolvedMember resolved(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType resultType,
            @Nullable Object declarationSite
    ) {
        return new FrontendResolvedMember(
                memberName,
                bindingKind,
                FrontendMemberResolutionStatus.RESOLVED,
                receiverKind,
                ownerKind,
                receiverType,
                Objects.requireNonNull(resultType, "resultType must not be null"),
                declarationSite,
                null
        );
    }

    public static @NotNull FrontendResolvedMember blocked(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType resultType,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedMember(
                memberName,
                bindingKind,
                FrontendMemberResolutionStatus.BLOCKED,
                receiverKind,
                ownerKind,
                receiverType,
                Objects.requireNonNull(resultType, "resultType must not be null"),
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedMember deferred(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedMember(
                memberName,
                bindingKind,
                FrontendMemberResolutionStatus.DEFERRED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedMember dynamic(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedMember(
                memberName,
                bindingKind,
                FrontendMemberResolutionStatus.DYNAMIC,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedMember failed(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedMember(
                memberName,
                bindingKind,
                FrontendMemberResolutionStatus.FAILED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedMember unsupported(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedMember(
                memberName,
                bindingKind,
                FrontendMemberResolutionStatus.UNSUPPORTED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                declarationSite,
                detailReason
        );
    }

    private static void validateState(
            @NotNull FrontendBindingKind bindingKind,
            @NotNull FrontendMemberResolutionStatus status,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType resultType,
            @Nullable String detailReason
    ) {
        if (status == FrontendMemberResolutionStatus.RESOLVED
                || status == FrontendMemberResolutionStatus.BLOCKED) {
            if (bindingKind == FrontendBindingKind.UNKNOWN) {
                throw new IllegalArgumentException("bindingKind must not be UNKNOWN for published member hits");
            }
            if (receiverKind == FrontendReceiverKind.UNKNOWN) {
                throw new IllegalArgumentException("receiverKind must not be UNKNOWN for published member hits");
            }
            Objects.requireNonNull(resultType, "resultType must not be null for resolved/blocked member results");
            if (status == FrontendMemberResolutionStatus.RESOLVED && detailReason != null) {
                throw new IllegalArgumentException("detailReason must be null for RESOLVED member results");
            }
            if (status == FrontendMemberResolutionStatus.BLOCKED) {
                requireNonBlank(detailReason, "detailReason");
            }
            return;
        }

        if (resultType != null) {
            throw new IllegalArgumentException("resultType must be null for non-success member results");
        }
        requireNonBlank(detailReason, "detailReason");
        if (status == FrontendMemberResolutionStatus.DYNAMIC
                && receiverKind == FrontendReceiverKind.UNKNOWN) {
            throw new IllegalArgumentException("receiverKind must not be UNKNOWN for DYNAMIC member results");
        }
    }

    private static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
