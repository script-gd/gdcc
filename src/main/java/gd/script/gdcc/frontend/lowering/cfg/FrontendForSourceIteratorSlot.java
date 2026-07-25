package gd.script.gdcc.frontend.lowering.cfg;

import dev.superice.gdparser.frontend.ast.ForStatement;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Source-facing iterator local slot published for one compile-ready `ForStatement`.
///
/// This is the dedicated discovery/type path for the iterator variable that body statements read
/// (for example `i`). It is intentionally separate from the hidden loop-carried iterator state slot:
/// the source slot is an ordinary source-visible local declared from `slotTypes()[ForStatement]`,
/// while the hidden state slot carries compiler-only storage. The two never share id, type or
/// registry even though both are keyed by the same `ForStatement` identity.
///
/// @param statement            owning `ForStatement`; also the registry key and the iterator declaration
///                  identity, so it must be identity-equal across plan, region, get item and this slot
/// @param sourceIteratorSlotId source iterator variable name (for example `i`); equal to
///                             `FrontendForIterationPlan.iteratorName()` and `ForStatement.iterator()`,
///                             never a synthetic hidden-slot name or a CFG value id
/// @param exposedType          source-facing iterator local type; must equal the final
///                    `slotTypes()[ForStatement]` and must be an ordinary `GdType`, never a
///                    `GdCompilerType`
public record FrontendForSourceIteratorSlot(
        @NotNull ForStatement statement,
        @NotNull String sourceIteratorSlotId,
        @NotNull GdType exposedType
) {
    public FrontendForSourceIteratorSlot {
        Objects.requireNonNull(statement, "statement must not be null");
        sourceIteratorSlotId = FrontendCfgGraph.validateNodeId(sourceIteratorSlotId, "sourceIteratorSlotId");
        Objects.requireNonNull(exposedType, "exposedType must not be null");
        if (exposedType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalArgumentException(
                    "Source-facing for-in iterator slot must not use compiler-only type "
                            + compilerOnlyType.getTypeName()
            );
        }
    }
}
