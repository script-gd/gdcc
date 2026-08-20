package gd.script.gdcc.frontend.lowering.cfg;

import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Immutable frontend-only CFG graph published after source control-flow has been materialized.
///
/// This graph intentionally stays independent from LIR basic blocks:
/// - node ids are frontend-local stable names
/// - `BranchNode` keeps the source condition fragment root and value id without forcing bool
///   normalization
/// - `SequenceNode` carries linear source evaluation items instead of already-lowered instructions
///
/// The graph is expected to be self-contained when it is published into a
/// `FunctionLoweringContext`, so the constructor validates entry/successor references eagerly.
public record FrontendCfgGraph(
        @NotNull String entryNodeId,
        @NotNull Map<String, NodeDef> nodes
) {
    public FrontendCfgGraph {
        entryNodeId = validateNodeId(entryNodeId, "entryNodeId");
        nodes = copyNodes(nodes);
        validateEntryNode(nodes, entryNodeId);
        validateSuccessorTargets(nodes);
        validateMergeSourceContracts(nodes);
        validateValueProducerContracts(nodes);
        validateDirectSlotAliasContracts(nodes);
        validateWritableRouteContracts(nodes);
    }

    public boolean hasNode(@NotNull String nodeId) {
        return nodes.containsKey(validateNodeId(nodeId, "nodeId"));
    }

    public @Nullable NodeDef nodeOrNull(@NotNull String nodeId) {
        return nodes.get(validateNodeId(nodeId, "nodeId"));
    }

    public @NotNull NodeDef requireNode(@NotNull String nodeId) {
        var node = nodeOrNull(nodeId);
        if (node == null) {
            throw new IllegalStateException("Frontend CFG node has not been published: " + nodeId);
        }
        return node;
    }

    public @NotNull List<String> nodeIds() {
        return List.copyOf(nodes.keySet());
    }

    /// One frontend CFG node.
    public sealed interface NodeDef permits SequenceNode, BranchNode, StopNode {
        @NotNull String id();
    }

    /// Sequence node for straight-line execution.
    ///
    /// `nextId` always names the lexical continuation node after all items in this sequence have
    /// executed.
    public record SequenceNode(
            @NotNull String id,
            @NotNull List<SequenceItem> items,
            @NotNull String nextId
    ) implements NodeDef {
        public SequenceNode {
            id = validateNodeId(id, "id");
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
            nextId = validateNodeId(nextId, "nextId");
        }
    }

    /// Branch node for one source-level condition split.
    ///
    /// `conditionRoot` is the immediate tested condition fragment for this split: it must be the
    /// AST expression root whose evaluation directly published `conditionValueId`.
    /// This aligns the branch with the producer subtree, not with a uniquely recoverable producer
    /// item found by scanning the whole condition region.
    /// That means it may differ from the outer source-level condition root of the owning `if` /
    /// `elif` / `while` region:
    /// - plain `if flag` branches keep `flag`
    /// - `if not helper(seed)` keeps `helper(seed)`, because the builder flips branch targets
    ///   instead of materializing a separate `not ...` value
    /// - short-circuit `if a and b` keeps `a` on the first branch and `b` on the second
    ///
    /// `conditionValueId` is the fragment-local value already computed by the reachable condition
    /// region before this branch. Short-circuit lowering must keep each branch on its own
    /// fragment-local value id instead of reusing any outward-facing merged result slot from a
    /// branch-result merge expression. It may still be a non-bool source value; truthiness
    /// normalization is deferred to frontend CFG -> LIR lowering.
    public record BranchNode(
            @NotNull String id,
            @NotNull Expression conditionRoot,
            @NotNull String conditionValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) implements NodeDef {
        public BranchNode {
            id = validateNodeId(id, "id");
            Objects.requireNonNull(conditionRoot, "conditionRoot must not be null");
            conditionValueId = validateValueId(conditionValueId, "conditionValueId");
            trueTargetId = validateNodeId(trueTargetId, "trueTargetId");
            falseTargetId = validateNodeId(falseTargetId, "falseTargetId");
        }
    }

    /// Terminal node that closes one frontend control-flow path.
    ///
    /// `RETURN` models a real callable exit and may optionally carry a return value id.
    /// `TERMINAL_MERGE` is a synthetic anchor used only to represent "this structured construct fully
    /// terminates" inside the frontend graph; it must never pretend to be a real source `return`,
    /// an executable entry node, or an executable edge target.
    public record StopNode(
            @NotNull String id,
            @NotNull StopKind kind,
            @Nullable String returnValueIdOrNull
    ) implements NodeDef {
        public StopNode {
            id = validateNodeId(id, "id");
            Objects.requireNonNull(kind, "kind must not be null");
            returnValueIdOrNull = validateOptionalValueId(returnValueIdOrNull, "returnValueIdOrNull");
            if (kind == StopKind.TERMINAL_MERGE && returnValueIdOrNull != null) {
                throw new IllegalArgumentException("Terminal-merge stop node must not carry a return value id");
            }
        }
    }

    public enum StopKind {
        RETURN,
        TERMINAL_MERGE
    }

    private static @NotNull Map<String, NodeDef> copyNodes(@NotNull Map<String, NodeDef> nodes) {
        Objects.requireNonNull(nodes, "nodes must not be null");
        var copiedNodes = new LinkedHashMap<String, NodeDef>(nodes.size());
        for (var entry : nodes.entrySet()) {
            var nodeId = validateNodeId(entry.getKey(), "nodeId");
            var node = Objects.requireNonNull(entry.getValue(), "node must not be null");
            if (!node.id().equals(nodeId)) {
                throw new IllegalArgumentException(
                        "Frontend CFG node id mismatch: key '" + nodeId + "' does not match node.id '" + node.id() + "'"
                );
            }
            copiedNodes.put(nodeId, node);
        }
        return Collections.unmodifiableMap(copiedNodes);
    }

    private static void validateEntryNode(@NotNull Map<String, NodeDef> nodes, @NotNull String entryNodeId) {
        var entryNode = nodes.get(entryNodeId);
        if (entryNode == null) {
            throw new IllegalArgumentException("Frontend CFG entry node does not exist: " + entryNodeId);
        }
        if (entryNode instanceof StopNode stopNode && stopNode.kind() == StopKind.TERMINAL_MERGE) {
            throw new IllegalArgumentException(
                    "Frontend CFG entry node must not be a synthetic terminal-merge stop: " + entryNodeId
            );
        }
    }

    private static void validateSuccessorTargets(@NotNull Map<String, NodeDef> nodes) {
        for (var node : nodes.values()) {
            switch (node) {
                case SequenceNode(_, _, var nextId) -> validateTargetNode(nodes, node.id(), "nextId", nextId);
                case BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                    validateTargetNode(nodes, node.id(), "trueTargetId", trueTargetId);
                    validateTargetNode(nodes, node.id(), "falseTargetId", falseTargetId);
                }
                case StopNode _ -> {
                }
            }
        }
    }

    /// Frontend value ids are mostly single-definition, but branch-result merge slots are not:
    /// short-circuit lowering and later conditional-value lowering may write the same outward-facing
    /// result id along multiple mutually-exclusive paths.
    ///
    /// That exception is deliberately narrow. If a value id has more than one producer, every producer
    /// must be a `MergeValueItem`; mixed definitions such as `OpaqueExprValueItem + MergeValueItem` or
    /// `CallItem + MergeValueItem` are rejected at graph publication time.
    /// Merge-source locality is enforced separately by `validateMergeSourceContracts`
    /// (same-sequence producer, plus the narrow merge-of-merge exception).
    ///
    /// Any consumer that collects producers by value id must therefore handle multiple reaching
    /// producers instead of assuming a unique reverse lookup.
    private static void validateValueProducerContracts(@NotNull Map<String, NodeDef> nodes) {
        var producersByValueId = new LinkedHashMap<String, List<ValueProducerOccurrence>>();
        for (var node : nodes.values()) {
            if (!(node instanceof SequenceNode(var nodeId, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof ValueOpItem valueOpItem)) {
                    continue;
                }
                var resultValueId = valueOpItem.resultValueIdOrNull();
                if (resultValueId == null) {
                    continue;
                }
                producersByValueId.computeIfAbsent(resultValueId, _ -> new ArrayList<>())
                        .add(new ValueProducerOccurrence(nodeId, valueOpItem));
            }
        }
        for (var entry : producersByValueId.entrySet()) {
            var producers = entry.getValue();
            if (producers.size() <= 1) {
                continue;
            }
            if (producers.stream().allMatch(producer -> producer.item() instanceof MergeValueItem)) {
                continue;
            }
            throw new IllegalArgumentException(
                    "Frontend CFG value id '" + entry.getKey()
                            + "' has multiple producers, but only merge-result slots may do that: "
                            + describeProducers(producers)
            );
        }
    }

    /// Merge-slot writes are normally valid only when the branch-local source value has already
    /// been produced earlier in the same sequence node. That keeps short-circuit shapes explicit
    /// and prevents type collection from depending on cross-sequence traversal order.
    ///
    /// As a narrow exception, merge-of-merge is allowed: when the source value id has at least
    /// one producer anywhere in the graph and every such producer is itself a `MergeValueItem`
    /// (e.g. an inner conditional or a value-context `and/or` arm), the outer merge may source
    /// that shared slot even though it was not published locally. Any other cross-sequence source
    /// (opaque, call, const, ...) remains illegal, and a source with no producer anywhere still
    /// fail-fasts. The implementation must build a graph-wide producer index before deciding,
    /// and must not rely on an empty-stream `allMatch` vacuum (which would treat a dangling source
    /// as trivially merge-only).
    private static void validateMergeSourceContracts(@NotNull Map<String, NodeDef> nodes) {
        var producersByValueId = new LinkedHashMap<String, List<ValueOpItem>>();
        for (var node : nodes.values()) {
            if (!(node instanceof SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof ValueOpItem valueOpItem)) {
                    continue;
                }
                var resultValueId = valueOpItem.resultValueIdOrNull();
                if (resultValueId == null) {
                    continue;
                }
                producersByValueId
                        .computeIfAbsent(resultValueId, _ -> new ArrayList<>())
                        .add(valueOpItem);
            }
        }
        for (var node : nodes.values()) {
            if (!(node instanceof SequenceNode(var nodeId, var items, _))) {
                continue;
            }
            var locallyPublishedValueIds = new ArrayList<String>();
            for (var item : items) {
                if (item instanceof MergeValueItem mergeValueItem) {
                    var sourceValueId = mergeValueItem.sourceValueId();
                    var locallyProduced = locallyPublishedValueIds.contains(sourceValueId);
                    var globallyProducedAsMergeOnly = false;
                    var globalProducers = producersByValueId.get(sourceValueId);
                    if (globalProducers != null && !globalProducers.isEmpty()) {
                        globallyProducedAsMergeOnly =
                                globalProducers.stream().allMatch(producer -> producer instanceof MergeValueItem);
                    }
                    if (!locallyProduced && !globallyProducedAsMergeOnly) {
                        throw new IllegalArgumentException(
                                "Frontend CFG merge item in sequence '"
                                        + nodeId
                                        + "' must source a value produced earlier in the same sequence, but '"
                                        + sourceValueId
                                        + "' has no prior local producer"
                        );
                    }
                }
                if (item instanceof ValueOpItem valueOpItem && valueOpItem.resultValueIdOrNull() != null) {
                    locallyPublishedValueIds.add(valueOpItem.resultValueIdOrNull());
                }
            }
        }
    }

    private static void validateDirectSlotAliasContracts(@NotNull Map<String, NodeDef> nodes) {
        for (var node : nodes.values()) {
            if (!(node instanceof SequenceNode(var nodeId, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof DirectSlotAliasValueItem aliasValueItem)) {
                    continue;
                }
                if (!(aliasValueItem.expression() instanceof Expression)) {
                    throw new IllegalArgumentException(
                            "Frontend CFG direct-slot alias in sequence '"
                                    + nodeId
                                    + "' must anchor a lowering expression"
                    );
                }
                if (!aliasValueItem.operandValueIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Frontend CFG direct-slot alias in sequence '"
                                    + nodeId
                                    + "' must not carry operandValueIds"
                    );
                }
                if (aliasValueItem.resultValueId().isBlank()) {
                    throw new IllegalArgumentException(
                            "Frontend CFG direct-slot alias in sequence '"
                                    + nodeId
                                    + "' must publish one non-blank result value id"
                    );
                }
            }
        }
    }

    /// Writable-route payloads must stay self-contained at graph publication time:
    /// - every referenced value id must already have been published earlier in the same sequence
    /// - descriptor shapes must stay mechanically well-formed so later lowering never has to guess
    /// - payload-backed mutating calls must also publish one dedicated receiver value slot instead of
    ///   leaving body lowering to re-read the receiver leaf on demand
    private static void validateWritableRouteContracts(@NotNull Map<String, NodeDef> nodes) {
        for (var node : nodes.values()) {
            if (!(node instanceof SequenceNode(var nodeId, var items, _))) {
                continue;
            }
            var locallyPublishedValueIds = new ArrayList<String>();
            for (var item : items) {
                var payload = extractWritableRoutePayload(item);
                if (payload != null) {
                    validateCallWritableReceiverContract(item, nodeId);
                    for (var valueId : payload.referencedValueIds()) {
                        if (!locallyPublishedValueIds.contains(valueId)) {
                            throw new IllegalArgumentException(
                                    "Frontend CFG writable route in sequence '"
                                            + nodeId
                                            + "' references value id '"
                                            + valueId
                                            + "' before it is published locally"
                            );
                        }
                    }
                    validateStaticWritableRouteTerminalContract(payload, nodeId);
                }
                if (item instanceof ValueOpItem valueOpItem && valueOpItem.resultValueIdOrNull() != null) {
                    locallyPublishedValueIds.add(valueOpItem.resultValueIdOrNull());
                }
            }
        }
    }

    private static void validateCallWritableReceiverContract(@NotNull SequenceItem item, @NotNull String nodeId) {
        if (!(item instanceof CallItem callItem) || callItem.writableRoutePayloadOrNull() == null) {
            return;
        }
        if (callItem.receiverValueIdOrNull() == null) {
            throw new IllegalArgumentException(
                    "Frontend CFG writable call in sequence '"
                            + nodeId
                            + "' must publish dedicated receiverValueIdOrNull together with writableRoutePayload"
            );
        }
    }

    /// `StaticPropertyCommitStep` is not explicitly tagged on the payload surface; it is encoded as a
    /// `PROPERTY` descriptor rooted at `STATIC_CONTEXT` without a runtime container slot. Publication
    /// must reject non-terminal occurrences early so body lowering never reaches the later
    /// reverse-commit fail-fast path with a malformed chain.
    private static void validateStaticWritableRouteTerminalContract(
            @NotNull FrontendWritableRoutePayload payload,
            @NotNull String nodeId
    ) {
        if (payload.root().kind() != FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT) {
            return;
        }
        if (payload.leaf().kind() == FrontendWritableRoutePayload.LeafKind.PROPERTY
                && payload.leaf().containerValueIdOrNull() == null
                && !payload.reverseCommitSteps().isEmpty()) {
            throw new IllegalArgumentException(
                    "Frontend CFG writable route in sequence '"
                            + nodeId
                            + "' must keep a static property leaf terminal once rooted at STATIC_CONTEXT"
            );
        }
        for (var index = 1; index < payload.reverseCommitSteps().size(); index++) {
            var step = payload.reverseCommitSteps().get(index);
            if (step.kind() == FrontendWritableRoutePayload.StepKind.PROPERTY
                    && step.containerValueIdOrNull() == null) {
                throw new IllegalArgumentException(
                        "Frontend CFG writable route in sequence '"
                                + nodeId
                                + "' contains a non-terminal static property commit step"
                );
            }
        }
    }

    private static @Nullable FrontendWritableRoutePayload extractWritableRoutePayload(@NotNull SequenceItem item) {
        return switch (item) {
            case AssignmentItem assignmentItem -> assignmentItem.writableRoutePayload();
            case CallItem callItem -> callItem.writableRoutePayloadOrNull();
            default -> null;
        };
    }

    private static void validateTargetNode(
            @NotNull Map<String, NodeDef> nodes,
            @NotNull String sourceNodeId,
            @NotNull String edgeName,
            @NotNull String targetNodeId
    ) {
        var targetNode = nodes.get(targetNodeId);
        if (targetNode == null) {
            throw new IllegalArgumentException(
                    "Frontend CFG node '" + sourceNodeId + "' references missing " + edgeName + " '" + targetNodeId + "'"
            );
        }
        if (targetNode instanceof StopNode stopNode && stopNode.kind() == StopKind.TERMINAL_MERGE) {
            throw new IllegalArgumentException(
                    "Frontend CFG node '"
                            + sourceNodeId
                            + "' must not target synthetic terminal-merge stop '"
                            + targetNodeId
                            + "' via "
                            + edgeName
            );
        }
    }

    /// Shared identifier validator for node ids published anywhere inside the frontend CFG surface.
    ///
    /// Subpackages such as `cfg.item` and `cfg.region` reuse this helper so the graph and its
    /// auxiliary overlays all enforce one identical identifier contract.
    public static @NotNull String validateNodeId(@Nullable String id, @NotNull String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        return StringUtil.requireNonBlank(id, fieldName);
    }

    /// Shared validator for frontend-local value ids.
    ///
    /// Value ids currently follow the same non-null / non-blank rule as node ids, but exposing a
    /// dedicated helper keeps the call sites semantically explicit and leaves room for later value-id
    /// specific validation without changing every consumer.
    public static @NotNull String validateValueId(@Nullable String id, @NotNull String fieldName) {
        return validateNodeId(id, fieldName);
    }

    private static @Nullable String validateOptionalValueId(@Nullable String id, @NotNull String fieldName) {
        return id == null ? null : validateValueId(id, fieldName);
    }

    private static @NotNull String describeProducers(@NotNull List<ValueProducerOccurrence> producers) {
        var parts = new ArrayList<String>(producers.size());
        for (var producer : producers) {
            parts.add(producer.nodeId() + ":" + producer.item().getClass().getSimpleName());
        }
        return String.join(", ", parts);
    }

    private record ValueProducerOccurrence(
            @NotNull String nodeId,
            @NotNull ValueOpItem item
    ) {
    }
}
