package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.constant.Evaluation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Read-only view into an enclosing lexical environment's constant facts, handed to a nested
/// callable that must not expose a real environment parent.
///
/// Lambdas resolve with a deliberately parentless environment so mutable outer bindings can only
/// be reached through the capture boundary. Constants are exempt from that boundary — a frozen
/// value can never become a mutable alias — so the lambda environment reads them through this
/// narrow single-method surface instead of the full parent chain.
@FunctionalInterface
public interface FrontendConstFactView {
    /// The constant evaluation fact published for `declaration`, or `null` when the enclosing
    /// environment has never produced one for it.
    @Nullable Evaluation constantFact(@NotNull Node declaration);
}
