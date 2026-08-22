package gd.script.gdcc.frontend.lowering.cfg.item;

import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Commits the match subject into one pattern-bind source slot before the section body runs.
///
/// The item is the match analogue of `ForLoopGetItem`: it lives at the section body-entry sequence
/// head, consumes the already-evaluated subject temp, and publishes no ordinary result. Body
/// lowering materializes the subject to `slotTypes()[PatternBindingExpression]` then `AssignInsn`
/// into the predeclared bind slot. `LocalDeclarationItem` is not extended to carry this identity.
public record MatchBindItem(
        @NotNull PatternBindingExpression declaration,
        @NotNull String subjectValueId,
        @NotNull String bindSlotId
) implements ValueOpItem {
    public MatchBindItem {
        Objects.requireNonNull(declaration, "declaration must not be null");
        subjectValueId = FrontendCfgGraph.validateValueId(subjectValueId, "subjectValueId");
        bindSlotId = FrontendCfgGraph.validateNodeId(bindSlotId, "bindSlotId");
    }

    @Override
    public @NotNull PatternBindingExpression anchor() {
        return declaration;
    }

    @Override
    public @Nullable String resultValueIdOrNull() {
        return null;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(subjectValueId);
    }

    @Override
    public boolean hasStandaloneMaterializationSlot() {
        return false;
    }
}
