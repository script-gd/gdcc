package dev.superice.gdcc.frontend.lowering.cfg;

import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdparser.frontend.ast.Expression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    /// That means it may differ from the outer source-level condition root of the owning `if` /
    /// `elif` / `while` region:
    /// - plain `if flag` branches keep `flag`
    /// - `if not helper(seed)` keeps `helper(seed)`, because the builder flips branch targets
    ///   instead of materializing a separate `not ...` value
    /// - future short-circuit `if a and b` keeps `a` on the first branch and `b` on the second
    ///
    /// `conditionValueId` is the value already computed by the reachable condition region before
    /// this branch. It may still be a non-bool source value; truthiness normalization is deferred
    /// to frontend CFG -> LIR lowering.
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

    /// Terminal node that ends frontend control flow for the current function.
    public record StopNode(
            @NotNull String id,
            @Nullable String returnValueIdOrNull
    ) implements NodeDef {
        public StopNode {
            id = validateNodeId(id, "id");
            returnValueIdOrNull = validateOptionalValueId(returnValueIdOrNull, "returnValueIdOrNull");
        }
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
        if (!nodes.containsKey(entryNodeId)) {
            throw new IllegalArgumentException("Frontend CFG entry node does not exist: " + entryNodeId);
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

    private static void validateTargetNode(
            @NotNull Map<String, NodeDef> nodes,
            @NotNull String sourceNodeId,
            @NotNull String edgeName,
            @NotNull String targetNodeId
    ) {
        if (!nodes.containsKey(targetNodeId)) {
            throw new IllegalArgumentException(
                    "Frontend CFG node '" + sourceNodeId + "' references missing " + edgeName + " '" + targetNodeId + "'"
            );
        }
    }

    /// Shared identifier validator for node ids published anywhere inside the frontend CFG surface.
    ///
    /// Subpackages such as `cfg.item` and `cfg.region` reuse this helper so the graph and its
    /// auxiliary overlays all enforce one identical identifier contract.
    public static @NotNull String validateNodeId(@Nullable String id, @NotNull String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        var nonNullId = Objects.requireNonNull(id, fieldName + " must not be null");
        if (nonNullId.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return nonNullId;
    }

    /// Shared validator for frontend-local value ids.
    ///
    /// Value ids currently follow the same non-null / non-blank rule as node ids, but exposing a
    /// dedicated helper keeps the call sites semantically explicit and leaves room for future value-id
    /// specific validation without changing every consumer.
    public static @NotNull String validateValueId(@Nullable String id, @NotNull String fieldName) {
        return validateNodeId(id, fieldName);
    }

    private static @Nullable String validateOptionalValueId(@Nullable String id, @NotNull String fieldName) {
        return id == null ? null : validateValueId(id, fieldName);
    }
}
