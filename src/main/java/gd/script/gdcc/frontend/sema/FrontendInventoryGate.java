package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Typed-dependent subtree gate discovered by the Interface phase.
///
/// Interface discovery creates `PENDING + NOT_PUBLISHED` gates. Later body phases can classify the
/// header and publish the body inventory, but consumers must keep body lookup fail-closed until the
/// combined state reaches `SUPPORTED + PUBLISHED`.
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
        if (status != FrontendInventoryGateStatus.SUPPORTED
                && bodyInventoryReadiness != FrontendBodyInventoryReadiness.NOT_PUBLISHED) {
            throw new IllegalArgumentException("only supported inventory gates can publish body inventory");
        }
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

    public @NotNull FrontendInventoryGate withStatus(@NotNull FrontendInventoryGateStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        // Unsupported and pending gates never own a partially published body inventory.
        var nextReadiness = newStatus == FrontendInventoryGateStatus.SUPPORTED
                ? bodyInventoryReadiness
                : FrontendBodyInventoryReadiness.NOT_PUBLISHED;
        return new FrontendInventoryGate(owner, headerRoot, bodyRoot, deferredDomain, newStatus, nextReadiness);
    }

    public @NotNull FrontendInventoryGate withBodyInventoryReadiness(
            @NotNull FrontendBodyInventoryReadiness newReadiness
    ) {
        return new FrontendInventoryGate(
                owner,
                headerRoot,
                bodyRoot,
                deferredDomain,
                status,
                Objects.requireNonNull(newReadiness, "newReadiness")
        );
    }

    public boolean isBodyInventoryReady() {
        return status == FrontendInventoryGateStatus.SUPPORTED
                && bodyInventoryReadiness == FrontendBodyInventoryReadiness.PUBLISHED;
    }
}
