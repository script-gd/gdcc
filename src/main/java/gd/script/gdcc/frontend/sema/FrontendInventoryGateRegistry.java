package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Interface-layer registry for typed-dependent body gates.
///
/// The registry is the single body-readiness fact source for future resolver/binder consumers. A
/// missing gate or any state other than `SUPPORTED + PUBLISHED` must be interpreted as fail-closed.
public final class FrontendInventoryGateRegistry {
    private final @NotNull List<FrontendInventoryGate> gates = new ArrayList<>();
    private final @NotNull Map<Node, FrontendInventoryGate> gatesByBodyRoot;
    private final @NotNull Map<Node, List<FrontendInventoryGate>> gatesByOwner;
    private final @NotNull Map<Node, List<FrontendInventoryGate>> gatesByHeaderRoot;

    public FrontendInventoryGateRegistry(@NotNull List<FrontendInventoryGate> gates) {
        Objects.requireNonNull(gates, "gates");
        gatesByBodyRoot = new IdentityHashMap<>();
        gatesByOwner = new IdentityHashMap<>();
        gatesByHeaderRoot = new IdentityHashMap<>();
        for (var gate : gates) {
            register(gate);
        }
    }

    public static @NotNull FrontendInventoryGateRegistry empty() {
        return new FrontendInventoryGateRegistry(List.of());
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public @NotNull List<FrontendInventoryGate> gates() {
        return List.copyOf(gates);
    }

    public @Nullable FrontendInventoryGate gateForBodyRoot(@NotNull Node bodyRoot) {
        return gatesByBodyRoot.get(Objects.requireNonNull(bodyRoot, "bodyRoot"));
    }

    public @NotNull List<FrontendInventoryGate> gatesForOwner(@NotNull Node owner) {
        return copyIndexedGates(gatesByOwner, Objects.requireNonNull(owner, "owner"));
    }

    public @NotNull List<FrontendInventoryGate> gatesForHeaderRoot(@NotNull Node headerRoot) {
        return copyIndexedGates(gatesByHeaderRoot, Objects.requireNonNull(headerRoot, "headerRoot"));
    }

    public boolean isBodyInventoryReady(@NotNull Node bodyRoot) {
        var gate = gateForBodyRoot(bodyRoot);
        return gate != null && gate.isBodyInventoryReady();
    }

    public void register(@NotNull FrontendInventoryGate gate) {
        Objects.requireNonNull(gate, "gate");
        if (gatesByBodyRoot.containsKey(gate.bodyRoot())) {
            throw new IllegalArgumentException("duplicate inventory gate body root");
        }
        gates.add(gate);
        gatesByBodyRoot.put(gate.bodyRoot(), gate);
        gatesByOwner.computeIfAbsent(gate.owner(), _ -> new ArrayList<>()).add(gate);
        gatesByHeaderRoot.computeIfAbsent(gate.headerRoot(), _ -> new ArrayList<>()).add(gate);
    }

    public @NotNull FrontendInventoryGate updateStatus(
            @NotNull Node bodyRoot,
            @NotNull FrontendInventoryGateStatus status
    ) {
        return replaceGate(requireGateForBodyRoot(bodyRoot).withStatus(status));
    }

    public @NotNull FrontendInventoryGate updateBodyInventoryReadiness(
            @NotNull Node bodyRoot,
            @NotNull FrontendBodyInventoryReadiness readiness
    ) {
        return replaceGate(requireGateForBodyRoot(bodyRoot).withBodyInventoryReadiness(readiness));
    }

    public @NotNull FrontendInventoryGate markSupported(@NotNull Node bodyRoot) {
        return updateStatus(bodyRoot, FrontendInventoryGateStatus.SUPPORTED);
    }

    public @NotNull FrontendInventoryGate markUnsupported(@NotNull Node bodyRoot) {
        return updateStatus(bodyRoot, FrontendInventoryGateStatus.UNSUPPORTED);
    }

    public @NotNull FrontendInventoryGate markBodyInventoryPublishing(@NotNull Node bodyRoot) {
        return updateBodyInventoryReadiness(bodyRoot, FrontendBodyInventoryReadiness.PUBLISHING);
    }

    public @NotNull FrontendInventoryGate markBodyInventoryPublished(@NotNull Node bodyRoot) {
        return updateBodyInventoryReadiness(bodyRoot, FrontendBodyInventoryReadiness.PUBLISHED);
    }

    private @NotNull FrontendInventoryGate requireGateForBodyRoot(@NotNull Node bodyRoot) {
        var gate = gateForBodyRoot(bodyRoot);
        if (gate == null) {
            throw new IllegalArgumentException("missing inventory gate body root");
        }
        return gate;
    }

    private @NotNull FrontendInventoryGate replaceGate(@NotNull FrontendInventoryGate nextGate) {
        var previousGate = requireGateForBodyRoot(nextGate.bodyRoot());
        var gateIndex = gates.indexOf(previousGate);
        gates.set(gateIndex, nextGate);
        removeIndexedGate(gatesByOwner, previousGate.owner(), previousGate);
        removeIndexedGate(gatesByHeaderRoot, previousGate.headerRoot(), previousGate);
        gatesByBodyRoot.put(nextGate.bodyRoot(), nextGate);
        gatesByOwner.computeIfAbsent(nextGate.owner(), _ -> new ArrayList<>()).add(nextGate);
        gatesByHeaderRoot.computeIfAbsent(nextGate.headerRoot(), _ -> new ArrayList<>()).add(nextGate);
        return nextGate;
    }

    private static @NotNull List<FrontendInventoryGate> copyIndexedGates(
            @NotNull Map<Node, List<FrontendInventoryGate>> index,
            @NotNull Node key
    ) {
        var indexedGates = index.get(key);
        return indexedGates == null ? List.of() : List.copyOf(indexedGates);
    }

    private static void removeIndexedGate(
            @NotNull Map<Node, List<FrontendInventoryGate>> index,
            @NotNull Node key,
            @NotNull FrontendInventoryGate gate
    ) {
        var indexedGates = index.get(key);
        if (indexedGates == null) {
            return;
        }
        indexedGates.remove(gate);
        if (indexedGates.isEmpty()) {
            index.remove(key);
        }
    }

    public static final class Builder {
        private final @NotNull List<FrontendInventoryGate> gates = new ArrayList<>();

        private Builder() {
        }

        public @NotNull Builder add(@NotNull FrontendInventoryGate gate) {
            gates.add(Objects.requireNonNull(gate, "gate"));
            return this;
        }

        public @NotNull FrontendInventoryGateRegistry build() {
            return new FrontendInventoryGateRegistry(gates);
        }
    }
}
