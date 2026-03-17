package dev.superice.gdcc.frontend.sema.resolver;

import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.resolver.ScopeMethodResolver;
import dev.superice.gdcc.scope.resolver.ScopePropertyResolver;
import dev.superice.gdcc.scope.resolver.ScopeResolvedMethod;
import dev.superice.gdcc.scope.resolver.ScopeResolvedProperty;
import dev.superice.gdcc.scope.resolver.ScopeResolvedSignal;
import dev.superice.gdcc.scope.resolver.ScopeSignalResolver;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdSignalType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.UnknownAttributeStep;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/// Local left-to-right chain reduction helper used before the published chain analyzer exists.
///
/// The helper is frontend-specific because it translates shared resolver output into frontend
/// statuses, trace entries, and suggested `FrontendResolvedMember` / `FrontendResolvedCall` facts.
public final class FrontendChainReductionHelper {
    private FrontendChainReductionHelper() {
    }

    public enum Status {
        RESOLVED,
        BLOCKED,
        DEFERRED,
        DYNAMIC,
        FAILED,
        UNSUPPORTED
    }

    public enum StepKind {
        PROPERTY,
        CALL,
        SUBSCRIPT,
        UNKNOWN
    }

    public enum RouteKind {
        INSTANCE_PROPERTY,
        INSTANCE_SIGNAL,
        INSTANCE_METHOD,
        STATIC_METHOD,
        STATIC_LOAD,
        CONSTRUCTOR,
        SUBSCRIPT,
        UPSTREAM_BLOCKED,
        UPSTREAM_DEFERRED,
        UPSTREAM_DYNAMIC,
        UPSTREAM_FAILED,
        UPSTREAM_UNSUPPORTED
    }

    @FunctionalInterface
    public interface ExpressionTypeResolver {
        @NotNull ExpressionTypeResult resolve(@NotNull Expression expression, boolean finalizeWindow);
    }

    @FunctionalInterface
    public interface NoteSink {
        void accept(@NotNull ReductionNote note);
    }

    public record ReductionNote(
            @NotNull Node anchor,
            @NotNull String message
    ) {
        public ReductionNote {
            Objects.requireNonNull(anchor, "anchor must not be null");
            message = requireNonBlank(message, "message");
        }
    }

    public record ExpressionTypeResult(
            @NotNull Status status,
            @Nullable GdType type,
            @Nullable String detailReason
    ) {
        public ExpressionTypeResult {
            Objects.requireNonNull(status, "status must not be null");
            if (status == Status.RESOLVED) {
                Objects.requireNonNull(type, "type must not be null for resolved expression type result");
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved expression type result");
                }
            } else {
                if (status == Status.BLOCKED || status == Status.DYNAMIC) {
                    throw new IllegalArgumentException("expression type dependencies do not support " + status);
                }
                if (type != null) {
                    throw new IllegalArgumentException("type must be null for non-resolved expression type result");
                }
                requireNonBlank(detailReason, "detailReason");
            }
        }

        public static @NotNull ExpressionTypeResult resolved(@NotNull GdType type) {
            return new ExpressionTypeResult(Status.RESOLVED, Objects.requireNonNull(type, "type must not be null"), null);
        }

        public static @NotNull ExpressionTypeResult deferred(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.DEFERRED, null, detailReason);
        }

        public static @NotNull ExpressionTypeResult failed(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.FAILED, null, detailReason);
        }

        public static @NotNull ExpressionTypeResult unsupported(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.UNSUPPORTED, null, detailReason);
        }
    }

    public record ReceiverState(
            @NotNull Status status,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType receiverType,
            @Nullable ScopeTypeMeta receiverTypeMeta,
            @Nullable String detailReason
    ) {
        public ReceiverState {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(receiverKind, "receiverKind must not be null");
            if (status == Status.RESOLVED) {
                if (receiverKind != FrontendReceiverKind.INSTANCE && receiverKind != FrontendReceiverKind.TYPE_META) {
                    throw new IllegalArgumentException("resolved receiver state must be INSTANCE or TYPE_META");
                }
                Objects.requireNonNull(receiverType, "receiverType must not be null for resolved receiver state");
                if (receiverKind == FrontendReceiverKind.TYPE_META) {
                    Objects.requireNonNull(receiverTypeMeta, "receiverTypeMeta must not be null for type-meta receiver state");
                } else if (receiverTypeMeta != null) {
                    throw new IllegalArgumentException("receiverTypeMeta must be null for instance receiver state");
                }
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved receiver state");
                }
            } else {
                requireNonBlank(detailReason, "detailReason");
                if (status == Status.DYNAMIC && receiverKind != FrontendReceiverKind.DYNAMIC) {
                    throw new IllegalArgumentException("dynamic receiver state must use DYNAMIC receiver kind");
                }
            }
        }

        public static @NotNull ReceiverState resolvedInstance(@NotNull GdType receiverType) {
            return new ReceiverState(
                    Status.RESOLVED,
                    FrontendReceiverKind.INSTANCE,
                    Objects.requireNonNull(receiverType, "receiverType must not be null"),
                    null,
                    null
            );
        }

        public static @NotNull ReceiverState resolvedTypeMeta(@NotNull ScopeTypeMeta receiverTypeMeta) {
            var typeMeta = Objects.requireNonNull(receiverTypeMeta, "receiverTypeMeta must not be null");
            return new ReceiverState(
                    Status.RESOLVED,
                    FrontendReceiverKind.TYPE_META,
                    typeMeta.instanceType(),
                    typeMeta,
                    null
            );
        }

        public static @NotNull ReceiverState blockedFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.BLOCKED, detailReason);
        }

        public static @NotNull ReceiverState deferredFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.DEFERRED, detailReason);
        }

        public static @NotNull ReceiverState failedFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.FAILED, detailReason);
        }

        public static @NotNull ReceiverState unsupportedFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.UNSUPPORTED, detailReason);
        }

        public static @NotNull ReceiverState dynamic(@NotNull String detailReason) {
            return new ReceiverState(Status.DYNAMIC, FrontendReceiverKind.DYNAMIC, GdVariantType.VARIANT, null, detailReason);
        }

        private static @NotNull ReceiverState copyWithStatus(
                @NotNull ReceiverState baseState,
                @NotNull Status status,
                @NotNull String detailReason
        ) {
            var state = Objects.requireNonNull(baseState, "baseState must not be null");
            return new ReceiverState(
                    Objects.requireNonNull(status, "status must not be null"),
                    state.receiverKind(),
                    state.receiverType(),
                    state.receiverTypeMeta(),
                    detailReason
            );
        }
    }

    public record UpstreamCause(
            @NotNull OptionalInt sourceStepIndex,
            @NotNull Status status,
            @NotNull String detailReason
    ) {
        public UpstreamCause {
            Objects.requireNonNull(sourceStepIndex, "sourceStepIndex must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (status == Status.RESOLVED) {
                throw new IllegalArgumentException("upstream cause cannot be RESOLVED");
            }
            detailReason = requireNonBlank(detailReason, "detailReason");
        }
    }

    public record StepTrace(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull StepKind stepKind,
            @NotNull RouteKind routeKind,
            @NotNull ReceiverState incomingReceiver,
            @NotNull Status status,
            @NotNull ReceiverState outgoingReceiver,
            @Nullable UpstreamCause upstreamCause,
            @Nullable FrontendResolvedMember suggestedMember,
            @Nullable FrontendResolvedCall suggestedCall,
            boolean finalizeRetryUsed,
            @Nullable String detailReason
    ) {
        public StepTrace {
            if (stepIndex < 0) {
                throw new IllegalArgumentException("stepIndex must be >= 0");
            }
            Objects.requireNonNull(step, "step must not be null");
            Objects.requireNonNull(stepKind, "stepKind must not be null");
            Objects.requireNonNull(routeKind, "routeKind must not be null");
            Objects.requireNonNull(incomingReceiver, "incomingReceiver must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(outgoingReceiver, "outgoingReceiver must not be null");
            if (suggestedMember != null && suggestedCall != null) {
                throw new IllegalArgumentException("a step trace may suggest either a member or a call, not both");
            }
            if (status == Status.RESOLVED) {
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved step trace");
                }
                if (suggestedMember == null && suggestedCall == null) {
                    throw new IllegalArgumentException("resolved step trace must publish a member or call suggestion");
                }
            } else {
                requireNonBlank(detailReason, "detailReason");
            }
        }
    }

    public record ReductionRequest(
            @NotNull AttributeExpression chainExpression,
            @NotNull ReceiverState headReceiver,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry,
            @NotNull ExpressionTypeResolver expressionTypeResolver,
            @NotNull NoteSink noteSink
    ) {
        public ReductionRequest {
            Objects.requireNonNull(chainExpression, "chainExpression must not be null");
            Objects.requireNonNull(headReceiver, "headReceiver must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            Objects.requireNonNull(expressionTypeResolver, "expressionTypeResolver must not be null");
            Objects.requireNonNull(noteSink, "noteSink must not be null");
        }
    }

    public record ReductionResult(
            @NotNull List<StepTrace> stepTraces,
            @NotNull ReceiverState finalReceiver,
            @Nullable Node recoveryRoot,
            @NotNull List<ReductionNote> notes
    ) {
        public ReductionResult {
            stepTraces = List.copyOf(stepTraces);
            Objects.requireNonNull(finalReceiver, "finalReceiver must not be null");
            notes = List.copyOf(notes);
        }
    }

    public static @NotNull ReductionResult reduce(@NotNull ReductionRequest request) {
        var input = Objects.requireNonNull(request, "request must not be null");
        var traces = new ArrayList<StepTrace>();
        var notes = new ArrayList<ReductionNote>();
        var currentReceiver = input.headReceiver();
        Node recoveryRoot = currentReceiver.status() == Status.RESOLVED ? null : input.chainExpression().base();
        UpstreamCause upstreamCause = currentReceiver.status() == Status.RESOLVED
                ? null
                : new UpstreamCause(
                        OptionalInt.empty(),
                        currentReceiver.status(),
                        requireNonBlank(currentReceiver.detailReason(), "currentReceiver.detailReason")
                );

        for (var stepIndex = 0; stepIndex < input.chainExpression().steps().size(); stepIndex++) {
            var step = input.chainExpression().steps().get(stepIndex);
            StepTrace trace = currentReceiver.status() == Status.RESOLVED
                    ? reduceStep(stepIndex, step, currentReceiver, input, notes)
                    : propagateStep(stepIndex, step, currentReceiver, Objects.requireNonNull(upstreamCause));
            traces.add(trace);
            currentReceiver = trace.outgoingReceiver();
            if (recoveryRoot == null && trace.status() != Status.RESOLVED) {
                recoveryRoot = step;
            }
            if (trace.status() != Status.RESOLVED) {
                upstreamCause = new UpstreamCause(
                        OptionalInt.of(stepIndex),
                        trace.status(),
                        Objects.requireNonNull(trace.detailReason())
                );
            }
        }

        return new ReductionResult(traces, currentReceiver, recoveryRoot, notes);
    }

    private static @NotNull StepTrace reduceStep(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes
    ) {
        return switch (step) {
            case AttributePropertyStep propertyStep ->
                    reducePropertyStep(stepIndex, propertyStep, incomingReceiver, request);
            case AttributeCallStep callStep -> reduceCallStep(stepIndex, callStep, incomingReceiver, request, notes);
            case AttributeSubscriptStep subscriptStep ->
                    unsupportedSubscriptStep(stepIndex, subscriptStep, incomingReceiver);
            case UnknownAttributeStep unknownStep -> unsupportedUnknownStep(stepIndex, unknownStep, incomingReceiver);
        };
    }

    private static @NotNull StepTrace reducePropertyStep(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request
    ) {
        if (incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META) {
            var detailReason = "Static load route for '" + step.name() + "' remains frontend-only until milestone C";
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.PROPERTY,
                    RouteKind.STATIC_LOAD,
                    incomingReceiver,
                    Status.UNSUPPORTED,
                    ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                    null,
                    FrontendResolvedMember.unsupported(
                            step.name(),
                            FrontendBindingKind.UNKNOWN,
                            FrontendReceiverKind.TYPE_META,
                            null,
                            incomingReceiver.receiverType(),
                            incomingReceiver.receiverTypeMeta(),
                            detailReason
                    ),
                    null,
                    false,
                    detailReason
            );
        }

        var receiverType = Objects.requireNonNull(incomingReceiver.receiverType(), "receiverType must not be null");
        if (receiverType instanceof GdVariantType) {
            var detailReason = "Variant receiver routes property access '" + step.name() + "' through runtime-dynamic semantics";
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.PROPERTY,
                    RouteKind.INSTANCE_PROPERTY,
                    incomingReceiver,
                    Status.DYNAMIC,
                    ReceiverState.dynamic(detailReason),
                    null,
                    FrontendResolvedMember.dynamic(
                            step.name(),
                            FrontendBindingKind.UNKNOWN,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            receiverType,
                            null,
                            detailReason
                    ),
                    null,
                    false,
                    detailReason
            );
        }

        if (!(receiverType instanceof GdObjectType objectType)) {
            return reduceBuiltinPropertyStep(stepIndex, step, incomingReceiver, request.classRegistry(), receiverType);
        }

        var propertyResult = ScopePropertyResolver.resolveObjectProperty(request.classRegistry(), objectType, step.name());
        if (propertyResult instanceof ScopePropertyResolver.Resolved(var property)) {
            return resolvedPropertyTrace(stepIndex, step, incomingReceiver, property);
        }

        var signalResult = ScopeSignalResolver.resolveInstanceSignal(request.classRegistry(), receiverType, step.name());
        if (signalResult instanceof ScopeSignalResolver.Resolved(var signal)) {
            return resolvedSignalTrace(stepIndex, step, incomingReceiver, signal);
        }

        if (propertyResult instanceof ScopePropertyResolver.MetadataUnknown
                || signalResult instanceof ScopeSignalResolver.MetadataUnknown) {
            var detailReason = "Receiver metadata for object type '" + receiverType.getTypeName()
                    + "' is not available yet while resolving member '" + step.name() + "'";
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.PROPERTY,
                    RouteKind.INSTANCE_PROPERTY,
                    incomingReceiver,
                    Status.DEFERRED,
                    ReceiverState.deferredFrom(incomingReceiver, detailReason),
                    null,
                    FrontendResolvedMember.deferred(
                            step.name(),
                            FrontendBindingKind.UNKNOWN,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            receiverType,
                            null,
                            detailReason
                    ),
                    null,
                    false,
                    detailReason
            );
        }

        var propertyFailed = assertPropertyFailure(propertyResult);
        var signalFailed = assertSignalFailure(signalResult);
        var detailReason = propertyFailed.kind() == ScopePropertyResolver.FailureKind.PROPERTY_MISSING
                && signalFailed.kind() == ScopeSignalResolver.FailureKind.SIGNAL_MISSING
                ? "Member '" + step.name() + "' was not found as a property or signal on type '" + receiverType.getTypeName() + "'"
                : renderMemberFailure(propertyFailed, signalFailed);
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                propertyFailed.kind() == ScopePropertyResolver.FailureKind.PROPERTY_MISSING
                        ? RouteKind.INSTANCE_SIGNAL
                        : RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.FAILED,
                ReceiverState.failedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.failed(
                        step.name(),
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        null,
                        receiverType,
                        null,
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace reduceBuiltinPropertyStep(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType receiverType
    ) {
        var propertyResult = ScopePropertyResolver.resolveBuiltinProperty(classRegistry, receiverType, step.name());
        if (propertyResult instanceof ScopePropertyResolver.Resolved(var property)) {
            return resolvedPropertyTrace(stepIndex, step, incomingReceiver, property);
        }
        var failed = assertPropertyFailure(propertyResult);
        var detailReason = "Builtin member lookup for '" + step.name() + "' failed on type '"
                + receiverType.getTypeName() + "': " + failed.kind();
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.FAILED,
                ReceiverState.failedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.failed(
                        step.name(),
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        null,
                        receiverType,
                        null,
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace resolvedPropertyTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedProperty property
    ) {
        var member = FrontendResolvedMember.resolved(
                step.name(),
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                property.ownerKind(),
                incomingReceiver.receiverType(),
                property.property().getType(),
                property.property()
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(property.property().getType()),
                null,
                member,
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace resolvedSignalTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedSignal signal
    ) {
        var signalType = GdSignalType.from(signal.signal());
        var member = FrontendResolvedMember.resolved(
                step.name(),
                FrontendBindingKind.SIGNAL,
                FrontendReceiverKind.INSTANCE,
                signal.ownerKind(),
                incomingReceiver.receiverType(),
                signalType,
                signal.signal()
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_SIGNAL,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(signalType),
                null,
                member,
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace reduceCallStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes
    ) {
        if (incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META && step.name().equals("new")) {
            var typeMeta = Objects.requireNonNull(incomingReceiver.receiverTypeMeta(), "receiverTypeMeta must not be null");
            var detailReason = "Constructor route for type-meta receiver '" + typeMeta.sourceName()
                    + "' remains frontend-only until milestone C";
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.CALL,
                    RouteKind.CONSTRUCTOR,
                    incomingReceiver,
                    Status.UNSUPPORTED,
                    ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                    null,
                    null,
                    FrontendResolvedCall.unsupported(
                            step.name(),
                            FrontendCallResolutionKind.CONSTRUCTOR,
                            FrontendReceiverKind.TYPE_META,
                            null,
                            incomingReceiver.receiverType(),
                            List.of(),
                            typeMeta,
                            detailReason
                    ),
                    false,
                    detailReason
            );
        }

        var argumentResolution = resolveArgumentTypes(step.arguments(), request);
        if (argumentResolution.status() != Status.RESOLVED) {
            return unresolvedCallDependencyTrace(stepIndex, step, incomingReceiver, argumentResolution);
        }

        if (incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META) {
            return reduceStaticMethodStep(stepIndex, step, incomingReceiver, request.classRegistry(), argumentResolution.argumentTypes());
        }
        return reduceInstanceMethodStep(
                stepIndex,
                step,
                incomingReceiver,
                request.classRegistry(),
                argumentResolution.argumentTypes(),
                argumentResolution.retryUsed(),
                notes,
                request.noteSink()
        );
    }

    private static @NotNull StepTrace reduceStaticMethodStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<GdType> argumentTypes
    ) {
        var receiverTypeMeta = Objects.requireNonNull(incomingReceiver.receiverTypeMeta(), "receiverTypeMeta must not be null");
        var result = ScopeMethodResolver.resolveStaticMethod(classRegistry, receiverTypeMeta, step.name(), argumentTypes);
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> resolvedCallTrace(
                    stepIndex,
                    step,
                    incomingReceiver,
                    RouteKind.STATIC_METHOD,
                    FrontendReceiverKind.TYPE_META,
                    FrontendCallResolutionKind.STATIC_METHOD,
                    resolved.method(),
                    argumentTypes,
                    false
            );
            case ScopeMethodResolver.DynamicFallback fallback -> {
                var detailReason = "Static method lookup for '" + step.name() + "' unexpectedly returned dynamic fallback: " + fallback.reason();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.STATIC_METHOD,
                        incomingReceiver,
                        Status.DYNAMIC,
                        ReceiverState.dynamic(detailReason),
                        null,
                        null,
                        FrontendResolvedCall.dynamic(
                                step.name(),
                                FrontendReceiverKind.TYPE_META,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                receiverTypeMeta,
                                detailReason
                        ),
                        false,
                        detailReason
                );
            }
            case ScopeMethodResolver.Failed failed -> {
                var detailReason = "Static method lookup for '" + step.name() + "' on type '"
                        + receiverTypeMeta.sourceName() + "' failed: " + failed.kind();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.STATIC_METHOD,
                        incomingReceiver,
                        Status.FAILED,
                        ReceiverState.failedFrom(incomingReceiver, detailReason),
                        null,
                        null,
                        FrontendResolvedCall.failed(
                                step.name(),
                                FrontendCallResolutionKind.STATIC_METHOD,
                                FrontendReceiverKind.TYPE_META,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                receiverTypeMeta,
                                detailReason
                        ),
                        false,
                        detailReason
                );
            }
        };
    }

    private static @NotNull StepTrace reduceInstanceMethodStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull List<GdType> argumentTypes,
            boolean finalizeRetryUsed,
            @NotNull List<ReductionNote> notes,
            @NotNull NoteSink noteSink
    ) {
        var result = ScopeMethodResolver.resolveInstanceMethod(
                classRegistry,
                Objects.requireNonNull(incomingReceiver.receiverType(), "receiverType must not be null"),
                step.name(),
                argumentTypes
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> {
                var resolvedMethod = resolved.method();
                var routeKind = resolvedMethod.isStatic() ? RouteKind.STATIC_METHOD : RouteKind.INSTANCE_METHOD;
                var callKind = resolvedMethod.isStatic()
                        ? FrontendCallResolutionKind.STATIC_METHOD
                        : FrontendCallResolutionKind.INSTANCE_METHOD;
                if (resolvedMethod.isStatic()) {
                    emitNote(
                            step,
                            "Instance-style syntax resolved to static method '" + resolvedMethod.ownerClass().getName()
                                    + "." + resolvedMethod.methodName() + "'",
                            notes,
                            noteSink
                    );
                }
                yield resolvedCallTrace(
                        stepIndex,
                        step,
                        incomingReceiver,
                        routeKind,
                        FrontendReceiverKind.INSTANCE,
                        callKind,
                        resolvedMethod,
                        argumentTypes,
                        finalizeRetryUsed
                );
            }
            case ScopeMethodResolver.DynamicFallback fallback -> {
                var detailReason = "Method lookup for '" + step.name() + "' chose runtime-dynamic fallback: " + fallback.reason();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.INSTANCE_METHOD,
                        incomingReceiver,
                        Status.DYNAMIC,
                        ReceiverState.dynamic(detailReason),
                        null,
                        null,
                        FrontendResolvedCall.dynamic(
                                step.name(),
                                FrontendReceiverKind.INSTANCE,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                null,
                                detailReason
                        ),
                        finalizeRetryUsed,
                        detailReason
                );
            }
            case ScopeMethodResolver.Failed failed -> {
                var detailReason = "Method lookup for '" + step.name() + "' on type '"
                        + incomingReceiver.receiverType().getTypeName() + "' failed: " + failed.kind();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.INSTANCE_METHOD,
                        incomingReceiver,
                        Status.FAILED,
                        ReceiverState.failedFrom(incomingReceiver, detailReason),
                        null,
                        null,
                        FrontendResolvedCall.failed(
                                step.name(),
                                FrontendCallResolutionKind.INSTANCE_METHOD,
                                FrontendReceiverKind.INSTANCE,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                null,
                                detailReason
                        ),
                        finalizeRetryUsed,
                        detailReason
                );
            }
        };
    }

    private static @NotNull StepTrace resolvedCallTrace(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull RouteKind routeKind,
            @NotNull FrontendReceiverKind receiverKind,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull ScopeResolvedMethod resolvedMethod,
            @NotNull List<GdType> argumentTypes,
            boolean finalizeRetryUsed
    ) {
        var call = FrontendResolvedCall.resolved(
                step.name(),
                callKind,
                receiverKind,
                resolvedMethod.ownerKind(),
                incomingReceiver.receiverType(),
                resolvedMethod.returnType(),
                argumentTypes,
                resolvedMethod.function()
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.CALL,
                routeKind,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(resolvedMethod.returnType()),
                null,
                null,
                call,
                finalizeRetryUsed,
                null
        );
    }

    private static @NotNull StepTrace unresolvedCallDependencyTrace(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ArgumentResolution argumentResolution
    ) {
        var detailReason = Objects.requireNonNull(argumentResolution.detailReason(), "detailReason must not be null");
        var status = argumentResolution.status();
        var outgoingReceiver = switch (status) {
            case DEFERRED -> ReceiverState.deferredFrom(incomingReceiver, detailReason);
            case FAILED -> ReceiverState.failedFrom(incomingReceiver, detailReason);
            case UNSUPPORTED -> ReceiverState.unsupportedFrom(incomingReceiver, detailReason);
            default -> throw new IllegalStateException("unexpected argument status: " + status);
        };
        var callKind = incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META
                ? FrontendCallResolutionKind.STATIC_METHOD
                : FrontendCallResolutionKind.INSTANCE_METHOD;
        var call = switch (status) {
            case DEFERRED -> FrontendResolvedCall.deferred(
                    step.name(),
                    callKind,
                    incomingReceiver.receiverKind(),
                    null,
                    incomingReceiver.receiverType(),
                    argumentResolution.argumentTypes(),
                    null,
                    detailReason
            );
            case FAILED -> FrontendResolvedCall.failed(
                    step.name(),
                    callKind,
                    incomingReceiver.receiverKind(),
                    null,
                    incomingReceiver.receiverType(),
                    argumentResolution.argumentTypes(),
                    null,
                    detailReason
            );
            case UNSUPPORTED -> FrontendResolvedCall.unsupported(
                    step.name(),
                    callKind,
                    incomingReceiver.receiverKind(),
                    null,
                    incomingReceiver.receiverType(),
                    argumentResolution.argumentTypes(),
                    null,
                    detailReason
            );
            default -> throw new IllegalStateException("unexpected argument status: " + status);
        };
        return new StepTrace(
                stepIndex,
                step,
                StepKind.CALL,
                incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META
                        ? RouteKind.STATIC_METHOD
                        : RouteKind.INSTANCE_METHOD,
                incomingReceiver,
                status,
                outgoingReceiver,
                null,
                null,
                call,
                argumentResolution.retryUsed(),
                detailReason
        );
    }

    private static @NotNull StepTrace unsupportedSubscriptStep(
            int stepIndex,
            @NotNull AttributeSubscriptStep step,
            @NotNull ReceiverState incomingReceiver
    ) {
        var detailReason = "Attribute subscript step '" + step.name() + "' is not supported in milestone B helper yet";
        return new StepTrace(
                stepIndex,
                step,
                StepKind.SUBSCRIPT,
                RouteKind.SUBSCRIPT,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                null,
                null,
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace unsupportedUnknownStep(
            int stepIndex,
            @NotNull UnknownAttributeStep step,
            @NotNull ReceiverState incomingReceiver
    ) {
        var detailReason = "Unknown attribute step '" + step.nodeType() + "' cannot enter exact chain reduction";
        return new StepTrace(
                stepIndex,
                step,
                StepKind.UNKNOWN,
                RouteKind.SUBSCRIPT,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                null,
                null,
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace propagateStep(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull UpstreamCause upstreamCause
    ) {
        var detailReason = renderUpstreamReason(upstreamCause);
        var routeKind = switch (upstreamCause.status()) {
            case BLOCKED -> RouteKind.UPSTREAM_BLOCKED;
            case DEFERRED -> RouteKind.UPSTREAM_DEFERRED;
            case DYNAMIC -> RouteKind.UPSTREAM_DYNAMIC;
            case FAILED -> RouteKind.UPSTREAM_FAILED;
            case UNSUPPORTED -> RouteKind.UPSTREAM_UNSUPPORTED;
            case RESOLVED -> throw new IllegalStateException("upstream cause cannot be resolved");
        };
        var outgoingReceiver = switch (upstreamCause.status()) {
            case BLOCKED -> ReceiverState.blockedFrom(incomingReceiver, detailReason);
            case DEFERRED -> ReceiverState.deferredFrom(incomingReceiver, detailReason);
            case DYNAMIC -> ReceiverState.dynamic(detailReason);
            case FAILED -> ReceiverState.failedFrom(incomingReceiver, detailReason);
            case UNSUPPORTED -> ReceiverState.unsupportedFrom(incomingReceiver, detailReason);
            case RESOLVED -> throw new IllegalStateException("upstream cause cannot be resolved");
        };
        return new StepTrace(
                stepIndex,
                step,
                classifyStepKind(step),
                routeKind,
                incomingReceiver,
                outgoingReceiver.status(),
                outgoingReceiver,
                upstreamCause,
                null,
                null,
                false,
                detailReason
        );
    }

    /// Call/subscript steps are the only places where B-phase local retry is needed right now.
    /// We keep the retry window bounded to two passes:
    /// - normal lookup from published side tables / current callback facts
    /// - one finalize-window retry for the same argument list
    private static @NotNull ArgumentResolution resolveArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull ReductionRequest request
    ) {
        var resolvedTypes = new ArrayList<GdType>(arguments.size());
        var deferredIndexes = new ArrayList<Integer>();
        for (var index = 0; index < arguments.size(); index++) {
            var expression = arguments.get(index);
            var result = lookupExpressionType(request, expression, false);
            switch (result.status()) {
                case RESOLVED -> resolvedTypes.add(Objects.requireNonNull(result.type()));
                case DEFERRED -> {
                    resolvedTypes.add(null);
                    deferredIndexes.add(index);
                }
                case FAILED, UNSUPPORTED -> {
                    return new ArgumentResolution(
                            result.status(),
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (index + 1) + " type is unavailable: " + result.detailReason(),
                            false
                    );
                }
                case BLOCKED, DYNAMIC ->
                        throw new IllegalStateException("unexpected dependency status: " + result.status());
            }
        }

        if (deferredIndexes.isEmpty()) {
            return new ArgumentResolution(Status.RESOLVED, List.copyOf(resolvedTypes), null, false);
        }

        for (var deferredIndex : deferredIndexes) {
            var expression = arguments.get(deferredIndex);
            var result = lookupExpressionType(request, expression, true);
            switch (result.status()) {
                case RESOLVED -> resolvedTypes.set(deferredIndex, Objects.requireNonNull(result.type()));
                case DEFERRED -> {
                    return new ArgumentResolution(
                            Status.DEFERRED,
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (deferredIndex + 1) + " type is still deferred after finalize-window retry: "
                                    + result.detailReason(),
                            true
                    );
                }
                case FAILED, UNSUPPORTED -> {
                    return new ArgumentResolution(
                            result.status(),
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (deferredIndex + 1) + " type became unavailable during finalize-window retry: "
                                    + result.detailReason(),
                            true
                    );
                }
                case BLOCKED, DYNAMIC ->
                        throw new IllegalStateException("unexpected dependency status: " + result.status());
            }
        }
        return new ArgumentResolution(Status.RESOLVED, List.copyOf(resolvedTypes), null, true);
    }

    private static @NotNull List<GdType> resolvedArgumentPrefix(@NotNull List<GdType> resolvedTypes) {
        var prefix = new ArrayList<GdType>(resolvedTypes.size());
        for (var resolvedType : resolvedTypes) {
            if (resolvedType == null) {
                break;
            }
            prefix.add(resolvedType);
        }
        return List.copyOf(prefix);
    }

    private static @NotNull ExpressionTypeResult lookupExpressionType(
            @NotNull ReductionRequest request,
            @NotNull Expression expression,
            boolean finalizeWindow
    ) {
        var publishedType = request.analysisData().expressionTypes().get(expression);
        if (publishedType != null) {
            return ExpressionTypeResult.resolved(publishedType);
        }
        return request.expressionTypeResolver().resolve(expression, finalizeWindow);
    }

    private static @NotNull StepKind classifyStepKind(@NotNull AttributeStep step) {
        return switch (step) {
            case AttributePropertyStep _ -> StepKind.PROPERTY;
            case AttributeCallStep _ -> StepKind.CALL;
            case AttributeSubscriptStep _ -> StepKind.SUBSCRIPT;
            case UnknownAttributeStep _ -> StepKind.UNKNOWN;
        };
    }

    private static void emitNote(
            @NotNull Node anchor,
            @NotNull String message,
            @NotNull List<ReductionNote> notes,
            @NotNull NoteSink noteSink
    ) {
        var note = new ReductionNote(anchor, message);
        notes.add(note);
        noteSink.accept(note);
    }

    private static @NotNull ScopePropertyResolver.Failed assertPropertyFailure(@NotNull Object result) {
        return switch (result) {
            case ScopePropertyResolver.Failed failed -> failed;
            default -> throw new IllegalStateException("expected property failure, got " + result);
        };
    }

    private static @NotNull ScopeSignalResolver.Failed assertSignalFailure(@NotNull Object result) {
        return switch (result) {
            case ScopeSignalResolver.Failed failed -> failed;
            default -> throw new IllegalStateException("expected signal failure, got " + result);
        };
    }

    private static @NotNull String renderMemberFailure(
            @NotNull ScopePropertyResolver.Failed propertyFailed,
            @NotNull ScopeSignalResolver.Failed signalFailed
    ) {
        if (propertyFailed.kind() != ScopePropertyResolver.FailureKind.PROPERTY_MISSING) {
            return "Property resolution failed for '" + propertyFailed.propertyName() + "': " + propertyFailed.kind();
        }
        if (signalFailed.kind() != ScopeSignalResolver.FailureKind.SIGNAL_MISSING) {
            return "Signal resolution failed for '" + signalFailed.signalName() + "': " + signalFailed.kind();
        }
        return "Member '" + propertyFailed.propertyName() + "' was not found as a property or signal";
    }

    private static @NotNull String renderUpstreamReason(@NotNull UpstreamCause upstreamCause) {
        var anchor = upstreamCause.sourceStepIndex().isEmpty()
                ? "chain head"
                : "upstream step #" + (upstreamCause.sourceStepIndex().getAsInt() + 1);
        return switch (upstreamCause.status()) {
            case BLOCKED -> "Exact suffix resolution is blocked by " + anchor + ": " + upstreamCause.detailReason();
            case DEFERRED ->
                    "Exact suffix resolution stays deferred because of " + anchor + ": " + upstreamCause.detailReason();
            case DYNAMIC ->
                    "Exact suffix resolution stops after runtime-dynamic route at " + anchor + ": " + upstreamCause.detailReason();
            case FAILED ->
                    "Exact suffix resolution stops after failed step at " + anchor + ": " + upstreamCause.detailReason();
            case UNSUPPORTED ->
                    "Exact suffix resolution is sealed by unsupported " + anchor + ": " + upstreamCause.detailReason();
            case RESOLVED -> throw new IllegalStateException("upstream cause cannot be resolved");
        };
    }

    private static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        var text = Objects.requireNonNull(value, fieldName + " must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private record ArgumentResolution(
            @NotNull Status status,
            @NotNull List<GdType> argumentTypes,
            @Nullable String detailReason,
            boolean retryUsed
    ) {
        private ArgumentResolution {
            Objects.requireNonNull(status, "status must not be null");
            argumentTypes = List.copyOf(argumentTypes);
            if (status == Status.RESOLVED) {
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved argument resolution");
                }
            } else {
                requireNonBlank(detailReason, "detailReason");
            }
        }
    }
}
