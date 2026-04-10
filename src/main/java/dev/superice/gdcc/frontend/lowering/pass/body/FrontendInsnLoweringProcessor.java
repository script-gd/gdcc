package dev.superice.gdcc.frontend.lowering.pass.body;

import dev.superice.gdcc.lir.LirBasicBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// One typed lowering unit selected from a registry by the current CFG/AST node class.
///
/// `TContext` carries already-frozen surrounding facts that are not encoded on the dispatched node
/// itself, such as the owning `AssignmentItem` or `OpaqueExprValueItem`. The returned
/// `LirBasicBlock` is the active continuation block after lowering: most processors keep using the
/// input block, but writable-route runtime gates may splice synthetic blocks and hand back the
/// continuation block that later lowering must append to.
interface FrontendInsnLoweringProcessor<TNode, TContext> {
    @NotNull Class<TNode> nodeType();

    @NotNull LirBasicBlock lower(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull TNode node,
            @Nullable TContext context
    );
}
