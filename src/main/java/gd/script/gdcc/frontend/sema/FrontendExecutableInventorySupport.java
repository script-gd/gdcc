package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.scope.BlockScopeKind;
import org.jetbrains.annotations.NotNull;

/// Shared semantic contract for executable block kinds whose callable-local value inventory is supported being
/// published by `FrontendVariableAnalyzer`.
public final class FrontendExecutableInventorySupport {
    private FrontendExecutableInventorySupport() {
    }

    public static boolean canPublishCallableLocalValueInventory(@NotNull BlockScopeKind kind) {
        return FrontendBodySemanticSupportPolicy.forBlockScopeKind(kind).publishesLexicalInventory();
    }

}
