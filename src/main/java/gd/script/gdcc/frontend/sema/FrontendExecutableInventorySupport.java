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

    /// Reports whether a block scope kind is a supported suite body root.
    ///
    /// This is the single body-entry semantic entry consumed by `FrontendInterfacePhase` when deciding which blocks
    /// become suite-entry roots. It must agree with the first certificate gate in
    /// `FrontendBodyStructuralCompleteness`, which reads
    /// `FrontendBodySemanticSupportPolicy.isSupportedSuiteBodyRoot()` directly; both consumers therefore route
    /// through the same semantic entry instead of inferring body-entry from inventory publication.
    ///
    /// @param kind the block scope kind whose body-entry support is being queried
    /// @return `true` when the kind maps to a policy that is a supported suite body root
    public static boolean isSupportedSuiteBodyRoot(@NotNull BlockScopeKind kind) {
        return FrontendBodySemanticSupportPolicy.forBlockScopeKind(kind).isSupportedSuiteBodyRoot();
    }

}
