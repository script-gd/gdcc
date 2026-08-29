package gd.script.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

/// One linear frontend action stored inside a `FrontendCfgGraph.SequenceNode`.
///
/// Sequence items do not own control-flow edges themselves. A `SequenceNode` executes them in
/// lexical order and then jumps to its `nextId`, while `BranchNode` and `StopNode` only observe the
/// value ids published by earlier `ValueOpItem`s. Keeping this contract explicit prevents later
/// passes from having to infer whether a graph element represents execution work or control flow.
/// `AssertItem` joins `SourceAnchorItem` as the other statement-shaped item with no published
/// value id: unlike `SourceAnchorItem` it still lowers into one real `assert` instruction.
public sealed interface SequenceItem permits SourceAnchorItem, ValueOpItem, AssertItem {
    /// AST root that owns this item for diagnostics, semantic side-table lookup, and later lowering.
    @NotNull Node anchor();
}
