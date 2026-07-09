package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared semantic contract for executable block kinds whose callable-local value inventory is supported being
/// published by `FrontendVariableAnalyzer`.
public final class FrontendExecutableInventorySupport {
    private FrontendExecutableInventorySupport() {
    }

    public static boolean canPublishCallableLocalValueInventory(@NotNull BlockScopeKind kind) {
        return switch (kind) {
            case FUNCTION_BODY,
                 CONSTRUCTOR_BODY,
                 BLOCK_STATEMENT,
                 IF_BODY,
                 ELIF_BODY,
                 ELSE_BODY,
                 WHILE_BODY -> true;
            default -> false;
        };
    }

    public static boolean isCallableLocalValueInventoryReady(
            @NotNull BlockScope blockScope,
            @Nullable Node bodyRoot,
            @NotNull FrontendInventoryGateRegistry gateRegistry
    ) {
        Objects.requireNonNull(blockScope, "blockScope");
        Objects.requireNonNull(gateRegistry, "gateRegistry");
        if (canPublishCallableLocalValueInventory(blockScope.kind())) {
            return true;
        }
        return bodyRoot != null && gateRegistry.isBodyInventoryReady(bodyRoot);
    }
}
