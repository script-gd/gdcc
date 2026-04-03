package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// One typed lowering unit selected from a registry by the current CFG/AST node class.
///
/// `TContext` carries already-frozen surrounding facts that are not encoded on the dispatched node
/// itself, such as the owning `AssignmentItem` or `OpaqueExprValueItem`.
interface FrontendInsnLoweringProcessor<TNode, TContext> {
    @NotNull Class<TNode> nodeType();

    void lower(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull TNode node,
            @Nullable TContext context
    );
}
