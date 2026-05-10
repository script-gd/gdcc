package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Result of subscript key/index materialization.
///
/// `slotId` and `type` describe the lowered key that must be passed to the emitted
/// `VariantGet*` / `VariantSet*` instruction. `accessKind` is computed from that materialized type,
/// not from the original source expression type.
record MaterializedSubscriptKey(
        @NotNull String slotId,
        @NotNull GdType type,
        @NotNull FrontendSubscriptAccessSupport.AccessKind accessKind
) {
    MaterializedSubscriptKey {
        slotId = StringUtil.requireNonBlank(slotId, "slotId");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(accessKind, "accessKind must not be null");
    }
}
