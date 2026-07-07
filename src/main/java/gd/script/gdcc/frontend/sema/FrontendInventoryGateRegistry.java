package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Interface-layer registry for typed-dependent body gates.
///
/// The registry is the single body-readiness fact source for future resolver/binder consumers. A
/// missing gate or any state other than `SUPPORTED + PUBLISHED` must be interpreted as fail-closed.
public final class FrontendInventoryGateRegistry {
    private final @NotNull List<FrontendInventoryGate> gates;
    private final @NotNull Map<Node, FrontendInventoryGate> gatesByBodyRoot;

    public FrontendInventoryGateRegistry(@NotNull List<FrontendInventoryGate> gates) {
        Objects.requireNonNull(gates, "gates");
        var copiedGates = List.copyOf(gates);
        var indexedGates = new IdentityHashMap<Node, FrontendInventoryGate>();
        for (var gate : copiedGates) {
            var previous = indexedGates.put(gate.bodyRoot(), gate);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate inventory gate body root");
            }
        }
        this.gates = copiedGates;
        gatesByBodyRoot = Collections.unmodifiableMap(indexedGates);
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public @NotNull List<FrontendInventoryGate> gates() {
        return gates;
    }

    public @Nullable FrontendInventoryGate gateForBodyRoot(@NotNull Node bodyRoot) {
        return gatesByBodyRoot.get(Objects.requireNonNull(bodyRoot, "bodyRoot"));
    }

    public boolean isBodyInventoryReady(@NotNull Node bodyRoot) {
        var gate = gateForBodyRoot(bodyRoot);
        return gate != null
                && gate.status() == FrontendInventoryGateStatus.SUPPORTED
                && gate.bodyInventoryReadiness() == FrontendBodyInventoryReadiness.PUBLISHED;
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
