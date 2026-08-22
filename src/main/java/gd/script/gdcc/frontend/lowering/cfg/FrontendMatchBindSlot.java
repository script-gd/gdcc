package gd.script.gdcc.frontend.lowering.cfg;

import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Source-facing pattern-bind slot published for one compile-ready `PatternBindingExpression`.
///
/// This is the dedicated discovery/type path for `var x` in a match pattern. It is intentionally
/// separate from ordinary `LocalDeclarationItem` locals: `declareSourceLocalSlots()` only scans
/// `VariableDeclaration` identities. The slot id is the source-facing bind name; the exposed type
/// must equal the final `slotTypes()[PatternBindingExpression]` and must be an ordinary `GdType`.
///
/// @param declaration     bind AST identity; also the registry key
/// @param section         owning match section; guard/body share this section's scope
/// @param bindSlotId      source-facing variable name (for example `bound`); never a synthetic
///                        hidden-slot name or a CFG value id
/// @param exposedType     source-facing bind type; must equal `slotTypes()[declaration]`
public record FrontendMatchBindSlot(
        @NotNull PatternBindingExpression declaration,
        @NotNull MatchSection section,
        @NotNull String bindSlotId,
        @NotNull GdType exposedType
) {
    public FrontendMatchBindSlot {
        Objects.requireNonNull(declaration, "declaration must not be null");
        Objects.requireNonNull(section, "section must not be null");
        bindSlotId = FrontendCfgGraph.validateNodeId(bindSlotId, "bindSlotId");
        Objects.requireNonNull(exposedType, "exposedType must not be null");
        if (exposedType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalArgumentException(
                    "Source-facing match bind slot must not use compiler-only type "
                            + compilerOnlyType.getTypeName()
            );
        }
        if (!bindSlotId.equals(declaration.name())) {
            throw new IllegalArgumentException(
                    "Match bind slot id must equal the source bind name, but got '"
                            + bindSlotId
                            + "' for '"
                            + declaration.name()
                            + "'"
            );
        }
    }
}
