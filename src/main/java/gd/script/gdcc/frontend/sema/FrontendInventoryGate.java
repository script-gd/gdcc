package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Typed-dependent subtree gate discovered by the Interface phase.
///
/// Phase B only creates `PENDING + NOT_PUBLISHED` gates. Later body phases may classify and publish
/// a gate, but consumers must keep body lookup fail-closed until readiness reaches `PUBLISHED`.
public record FrontendInventoryGate(
        @NotNull Node owner,
        @NotNull Node headerRoot,
        @NotNull Node bodyRoot,
        @NotNull FrontendVisibleValueDomain deferredDomain,
        @NotNull FrontendInventoryGateStatus status,
        @NotNull FrontendBodyInventoryReadiness bodyInventoryReadiness
) {
    public FrontendInventoryGate {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(headerRoot, "headerRoot");
        Objects.requireNonNull(bodyRoot, "bodyRoot");
        Objects.requireNonNull(deferredDomain, "deferredDomain");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(bodyInventoryReadiness, "bodyInventoryReadiness");
    }

    public static @NotNull FrontendInventoryGate pending(
            @NotNull Node owner,
            @NotNull Node headerRoot,
            @NotNull Node bodyRoot,
            @NotNull FrontendVisibleValueDomain deferredDomain
    ) {
        return new FrontendInventoryGate(
                owner,
                headerRoot,
                bodyRoot,
                deferredDomain,
                FrontendInventoryGateStatus.PENDING,
                FrontendBodyInventoryReadiness.NOT_PUBLISHED
        );
    }
}
