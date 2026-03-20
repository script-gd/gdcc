package dev.superice.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Pure status/object conversion bridge shared by the chain-binding and expression-typing analyzers.
///
/// The bridge freezes body-phase publication rules in one place so the analyzers no longer drift on:
/// - `BLOCKED` winner-type preservation
/// - `DYNAMIC` publishing as runtime-dynamic `Variant`
/// - upstream-blocked suffixes not inventing a fake precise type
///
/// This helper deliberately does not traverse ASTs, publish side tables, or emit diagnostics.
public final class FrontendChainStatusBridge {
    private FrontendChainStatusBridge() {
    }

    public static @NotNull FrontendChainReductionHelper.ExpressionTypeResult toExpressionTypeResult(
            @NotNull FrontendExpressionType expressionType
    ) {
        var published = Objects.requireNonNull(expressionType, "expressionType must not be null");
        return switch (published.status()) {
            case RESOLVED -> FrontendChainReductionHelper.ExpressionTypeResult.resolved(
                    Objects.requireNonNull(published.publishedType(), "publishedType must not be null")
            );
            case BLOCKED -> FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                    published.publishedType(),
                    requireDetailReason(published.detailReason())
            );
            case DEFERRED -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                    requireDetailReason(published.detailReason())
            );
            case DYNAMIC -> FrontendChainReductionHelper.ExpressionTypeResult.dynamic(
                    requireDetailReason(published.detailReason())
            );
            case FAILED -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                    requireDetailReason(published.detailReason())
            );
            case UNSUPPORTED -> FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                    requireDetailReason(published.detailReason())
            );
        };
    }

    public static @NotNull FrontendChainReductionHelper.ExpressionTypeResult toExpressionTypeResult(
            @NotNull FrontendChainReductionHelper.ReceiverState receiverState
    ) {
        var receiver = Objects.requireNonNull(receiverState, "receiverState must not be null");
        return switch (receiver.status()) {
            case RESOLVED -> FrontendChainReductionHelper.ExpressionTypeResult.resolved(
                    Objects.requireNonNull(receiver.receiverType(), "receiverType must not be null")
            );
            case BLOCKED -> FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                    receiver.receiverType(),
                    requireDetailReason(receiver.detailReason())
            );
            case DEFERRED -> FrontendChainReductionHelper.ExpressionTypeResult.deferred(
                    requireDetailReason(receiver.detailReason())
            );
            case DYNAMIC -> FrontendChainReductionHelper.ExpressionTypeResult.dynamic(
                    requireDetailReason(receiver.detailReason())
            );
            case FAILED -> FrontendChainReductionHelper.ExpressionTypeResult.failed(
                    requireDetailReason(receiver.detailReason())
            );
            case UNSUPPORTED -> FrontendChainReductionHelper.ExpressionTypeResult.unsupported(
                    requireDetailReason(receiver.detailReason())
            );
        };
    }

    /// Reduction-level publication needs one extra rule beyond a plain receiver-state conversion:
    /// when the last step is only echoing an upstream blocked dependency, downstream consumers must
    /// keep the `BLOCKED` status but drop the published winner type.
    public static @NotNull FrontendChainReductionHelper.ExpressionTypeResult toExpressionTypeResult(
            @NotNull FrontendChainReductionHelper.ReductionResult reductionResult
    ) {
        var reduction = Objects.requireNonNull(reductionResult, "reductionResult must not be null");
        var finalReceiver = reduction.finalReceiver();
        if (finalReceiver.status() == FrontendChainReductionHelper.Status.BLOCKED
                && isUpstreamBlockedSuffix(reduction)) {
            return FrontendChainReductionHelper.ExpressionTypeResult.blocked(
                    null,
                    requireDetailReason(finalReceiver.detailReason())
            );
        }
        return toExpressionTypeResult(finalReceiver);
    }

    public static @NotNull FrontendChainReductionHelper.ReceiverState toReceiverState(
            @NotNull FrontendChainReductionHelper.ExpressionTypeResult typeResult
    ) {
        var result = Objects.requireNonNull(typeResult, "typeResult must not be null");
        return switch (result.status()) {
            case RESOLVED -> FrontendChainReductionHelper.ReceiverState.resolvedInstance(
                    Objects.requireNonNull(result.type(), "type must not be null")
            );
            case BLOCKED -> result.type() != null
                    ? FrontendChainReductionHelper.ReceiverState.blockedFrom(
                    FrontendChainReductionHelper.ReceiverState.resolvedInstance(result.type()),
                    requireDetailReason(result.detailReason())
            )
                    : new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.BLOCKED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    requireDetailReason(result.detailReason())
            );
            case DEFERRED -> new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.DEFERRED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    requireDetailReason(result.detailReason())
            );
            case DYNAMIC -> FrontendChainReductionHelper.ReceiverState.dynamic(
                    requireDetailReason(result.detailReason())
            );
            case FAILED -> new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.FAILED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    requireDetailReason(result.detailReason())
            );
            case UNSUPPORTED -> new FrontendChainReductionHelper.ReceiverState(
                    FrontendChainReductionHelper.Status.UNSUPPORTED,
                    FrontendReceiverKind.UNKNOWN,
                    null,
                    null,
                    requireDetailReason(result.detailReason())
            );
        };
    }

    public static @NotNull FrontendExpressionType toPublishedExpressionType(
            @NotNull FrontendChainReductionHelper.ExpressionTypeResult typeResult
    ) {
        var result = Objects.requireNonNull(typeResult, "typeResult must not be null");
        return switch (result.status()) {
            case RESOLVED -> FrontendExpressionType.resolved(
                    Objects.requireNonNull(result.type(), "type must not be null")
            );
            case BLOCKED -> FrontendExpressionType.blocked(
                    result.type(),
                    requireDetailReason(result.detailReason())
            );
            case DEFERRED -> FrontendExpressionType.deferred(requireDetailReason(result.detailReason()));
            case DYNAMIC -> FrontendExpressionType.dynamic(requireDetailReason(result.detailReason()));
            case FAILED -> FrontendExpressionType.failed(requireDetailReason(result.detailReason()));
            case UNSUPPORTED -> FrontendExpressionType.unsupported(requireDetailReason(result.detailReason()));
        };
    }

    public static @NotNull FrontendExpressionType toPublishedExpressionType(
            @NotNull FrontendChainReductionHelper.ReceiverState receiverState
    ) {
        return toPublishedExpressionType(toExpressionTypeResult(receiverState));
    }

    public static @NotNull FrontendExpressionType toPublishedExpressionType(
            @NotNull FrontendChainReductionHelper.ReductionResult reductionResult
    ) {
        return toPublishedExpressionType(toExpressionTypeResult(reductionResult));
    }

    public static @NotNull FrontendExpressionType toPublishedExpressionType(
            @NotNull FrontendResolvedMember member
    ) {
        var resolvedMember = Objects.requireNonNull(member, "member must not be null");
        return switch (resolvedMember.status()) {
            case RESOLVED -> FrontendExpressionType.resolved(
                    Objects.requireNonNull(resolvedMember.resultType(), "resultType must not be null")
            );
            case BLOCKED -> FrontendExpressionType.blocked(
                    resolvedMember.resultType(),
                    requireDetailReason(resolvedMember.detailReason())
            );
            case DEFERRED -> FrontendExpressionType.deferred(requireDetailReason(resolvedMember.detailReason()));
            case DYNAMIC -> FrontendExpressionType.dynamic(requireDetailReason(resolvedMember.detailReason()));
            case FAILED -> FrontendExpressionType.failed(requireDetailReason(resolvedMember.detailReason()));
            case UNSUPPORTED -> FrontendExpressionType.unsupported(requireDetailReason(resolvedMember.detailReason()));
        };
    }

    public static @NotNull FrontendExpressionType toPublishedExpressionType(
            @NotNull FrontendResolvedCall call
    ) {
        var resolvedCall = Objects.requireNonNull(call, "call must not be null");
        return switch (resolvedCall.status()) {
            case RESOLVED -> FrontendExpressionType.resolved(
                    Objects.requireNonNull(resolvedCall.returnType(), "returnType must not be null")
            );
            case BLOCKED -> FrontendExpressionType.blocked(
                    resolvedCall.returnType(),
                    requireDetailReason(resolvedCall.detailReason())
            );
            case DEFERRED -> FrontendExpressionType.deferred(requireDetailReason(resolvedCall.detailReason()));
            case DYNAMIC -> FrontendExpressionType.dynamic(requireDetailReason(resolvedCall.detailReason()));
            case FAILED -> FrontendExpressionType.failed(requireDetailReason(resolvedCall.detailReason()));
            case UNSUPPORTED -> FrontendExpressionType.unsupported(requireDetailReason(resolvedCall.detailReason()));
        };
    }

    private static boolean isUpstreamBlockedSuffix(@NotNull FrontendChainReductionHelper.ReductionResult reductionResult) {
        if (reductionResult.stepTraces().isEmpty()) {
            return false;
        }
        return reductionResult.stepTraces().getLast().routeKind() == FrontendChainReductionHelper.RouteKind.UPSTREAM_BLOCKED;
    }

    private static @NotNull String requireDetailReason(String detailReason) {
        return Objects.requireNonNull(detailReason, "detailReason must not be null");
    }
}
