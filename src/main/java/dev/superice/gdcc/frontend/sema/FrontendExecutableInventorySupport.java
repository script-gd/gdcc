package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import org.jetbrains.annotations.NotNull;

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
}
