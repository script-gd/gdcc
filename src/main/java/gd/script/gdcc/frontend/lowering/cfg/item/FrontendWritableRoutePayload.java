package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Frozen writable access-route metadata attached to one `CallItem` or `AssignmentItem`.
///
/// This payload is intentionally separate from ordinary CFG value items:
/// - ordinary items still own value evaluation order and result publication
/// - the payload owns only the owner/leaf/writeback shape needed for later writable-route lowering
///
/// The structure mirrors the shared writable-route support on the body side:
/// - `root` describes where the route starts
/// - `leaf` describes the direct place touched by the current mutation/read
/// - `reverseCommitSteps` describe how a mutated leaf value must be written back outward
///
/// The payload deliberately avoids one flattened route-operand array. Each leaf/step descriptor instead
/// carries the exact container/key operands it needs, so later lowering can consume the route
/// mechanically without re-slicing a positional list or reinterpreting the original AST chain.
public record FrontendWritableRoutePayload(
        @NotNull Node routeAnchor,
        @NotNull RootDescriptor root,
        @NotNull LeafDescriptor leaf,
        @NotNull List<StepDescriptor> reverseCommitSteps
) {
    public FrontendWritableRoutePayload {
        Objects.requireNonNull(routeAnchor, "routeAnchor must not be null");
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(leaf, "leaf must not be null");
        reverseCommitSteps = List.copyOf(Objects.requireNonNull(
                reverseCommitSteps,
                "reverseCommitSteps must not be null"
        ));
        reverseCommitSteps.forEach(step -> Objects.requireNonNull(step, "reverseCommitSteps must not contain null"));
    }

    public @NotNull FrontendWritableRoutePayload withRouteAnchor(@NotNull Node newRouteAnchor) {
        return new FrontendWritableRoutePayload(
                Objects.requireNonNull(newRouteAnchor, "newRouteAnchor must not be null"),
                root,
                leaf,
                reverseCommitSteps
        );
    }

    public @NotNull List<String> referencedValueIds() {
        var referenced = new ArrayList<String>();
        root.appendReferencedValueIds(referenced);
        leaf.appendReferencedValueIds(referenced);
        for (var step : reverseCommitSteps) {
            step.appendReferencedValueIds(referenced);
        }
        return List.copyOf(referenced);
    }

    public record RootDescriptor(
            @NotNull RootKind kind,
            @NotNull Node anchor,
            @Nullable String valueIdOrNull
    ) {
        public RootDescriptor {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(anchor, "anchor must not be null");
            valueIdOrNull = FrontendCfgItemSupport.validateOptionalValueId(valueIdOrNull, "valueIdOrNull");
            if (kind == RootKind.VALUE_ID && valueIdOrNull == null) {
                throw new IllegalArgumentException("VALUE_ID root must publish valueIdOrNull");
            }
            if (kind != RootKind.VALUE_ID && valueIdOrNull != null) {
                throw new IllegalArgumentException(kind + " root must not publish valueIdOrNull");
            }
        }

        private void appendReferencedValueIds(@NotNull List<String> referencedValueIds) {
            if (valueIdOrNull != null) {
                referencedValueIds.add(valueIdOrNull);
            }
        }
    }

    public enum RootKind {
        DIRECT_SLOT,
        SELF_CONTEXT,
        STATIC_CONTEXT,
        VALUE_ID
    }

    public record LeafDescriptor(
            @NotNull LeafKind kind,
            @NotNull Node anchor,
            @Nullable String containerValueIdOrNull,
            @NotNull List<String> operandValueIds,
            @Nullable String memberNameOrNull,
            @Nullable FrontendSubscriptAccessSupport.AccessKind subscriptAccessKindOrNull
    ) {
        /// `containerValueIdOrNull` points at the already-published owner slot used for the direct leaf
        /// operation, while `operandValueIds` holds any additional frozen operands such as the one
        /// subscript key currently supported by the frontend writable-route surface.
        public LeafDescriptor {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(anchor, "anchor must not be null");
            containerValueIdOrNull = FrontendCfgItemSupport.validateOptionalValueId(
                    containerValueIdOrNull,
                    "containerValueIdOrNull"
            );
            operandValueIds = FrontendCfgItemSupport.copyValueIds(operandValueIds, "operandValueIds");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
            switch (kind) {
                case DIRECT_SLOT -> {
                    if (containerValueIdOrNull != null) {
                        throw new IllegalArgumentException("DIRECT_SLOT leaf must not publish containerValueIdOrNull");
                    }
                    if (!operandValueIds.isEmpty()) {
                        throw new IllegalArgumentException("DIRECT_SLOT leaf must not publish operandValueIds");
                    }
                    if (memberNameOrNull != null) {
                        throw new IllegalArgumentException("DIRECT_SLOT leaf must not publish memberNameOrNull");
                    }
                    if (subscriptAccessKindOrNull != null) {
                        throw new IllegalArgumentException("DIRECT_SLOT leaf must not publish subscriptAccessKindOrNull");
                    }
                }
                case PROPERTY -> {
                    if (!operandValueIds.isEmpty()) {
                        throw new IllegalArgumentException("PROPERTY leaf must not publish operandValueIds");
                    }
                    if (memberNameOrNull == null) {
                        throw new IllegalArgumentException("PROPERTY leaf must publish memberNameOrNull");
                    }
                    if (subscriptAccessKindOrNull != null) {
                        throw new IllegalArgumentException("PROPERTY leaf must not publish subscriptAccessKindOrNull");
                    }
                }
                case SUBSCRIPT -> {
                    if (operandValueIds.size() != 1) {
                        throw new IllegalArgumentException("SUBSCRIPT leaf currently requires exactly one key operand");
                    }
                    if (subscriptAccessKindOrNull == null) {
                        throw new IllegalArgumentException("SUBSCRIPT leaf must publish subscriptAccessKindOrNull");
                    }
                }
            }
        }

        private void appendReferencedValueIds(@NotNull List<String> referencedValueIds) {
            if (containerValueIdOrNull != null) {
                referencedValueIds.add(containerValueIdOrNull);
            }
            referencedValueIds.addAll(operandValueIds);
        }
    }

    public enum LeafKind {
        DIRECT_SLOT,
        PROPERTY,
        SUBSCRIPT
    }

    public record StepDescriptor(
            @NotNull StepKind kind,
            @NotNull Node anchor,
            @Nullable String containerValueIdOrNull,
            @NotNull List<String> operandValueIds,
            @Nullable String memberNameOrNull,
            @Nullable FrontendSubscriptAccessSupport.AccessKind subscriptAccessKindOrNull
    ) {
        /// Reverse-commit steps reuse the same per-descriptor container/operand encoding as the leaf so
        /// graph publication and body lowering only need one mechanical contract for property/subscript
        /// writeback, regardless of how deep the owner chain is.
        public StepDescriptor {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(anchor, "anchor must not be null");
            containerValueIdOrNull = FrontendCfgItemSupport.validateOptionalValueId(
                    containerValueIdOrNull,
                    "containerValueIdOrNull"
            );
            operandValueIds = FrontendCfgItemSupport.copyValueIds(operandValueIds, "operandValueIds");
            if (memberNameOrNull != null) {
                memberNameOrNull = StringUtil.requireNonBlank(memberNameOrNull, "memberNameOrNull");
            }
            switch (kind) {
                case DIRECT_SLOT -> {
                    // Direct-slot writeback targets the route root slot itself, so no container,
                    // key, or member payload is meaningful on this step kind.
                    if (containerValueIdOrNull != null) {
                        throw new IllegalArgumentException("DIRECT_SLOT step must not publish containerValueIdOrNull");
                    }
                    if (!operandValueIds.isEmpty()) {
                        throw new IllegalArgumentException("DIRECT_SLOT step must not publish operandValueIds");
                    }
                    if (memberNameOrNull != null) {
                        throw new IllegalArgumentException("DIRECT_SLOT step must not publish memberNameOrNull");
                    }
                    if (subscriptAccessKindOrNull != null) {
                        throw new IllegalArgumentException("DIRECT_SLOT step must not publish subscriptAccessKindOrNull");
                    }
                }
                case PROPERTY -> {
                    if (!operandValueIds.isEmpty()) {
                        throw new IllegalArgumentException("PROPERTY step must not publish operandValueIds");
                    }
                    if (memberNameOrNull == null) {
                        throw new IllegalArgumentException("PROPERTY step must publish memberNameOrNull");
                    }
                    if (subscriptAccessKindOrNull != null) {
                        throw new IllegalArgumentException("PROPERTY step must not publish subscriptAccessKindOrNull");
                    }
                }
                case SUBSCRIPT -> {
                    if (operandValueIds.size() != 1) {
                        throw new IllegalArgumentException("SUBSCRIPT step currently requires exactly one key operand");
                    }
                    if (subscriptAccessKindOrNull == null) {
                        throw new IllegalArgumentException("SUBSCRIPT step must publish subscriptAccessKindOrNull");
                    }
                }
            }
        }

        private void appendReferencedValueIds(@NotNull List<String> referencedValueIds) {
            if (containerValueIdOrNull != null) {
                referencedValueIds.add(containerValueIdOrNull);
            }
            referencedValueIds.addAll(operandValueIds);
        }
    }

    public enum StepKind {
        DIRECT_SLOT,
        PROPERTY,
        SUBSCRIPT
    }
}
