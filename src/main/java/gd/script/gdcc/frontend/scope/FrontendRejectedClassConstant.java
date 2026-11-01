package gd.script.gdcc.frontend.scope;

import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Recovery record for one class-level `const` declaration whose initializer could not produce a
/// usable frozen value.
///
/// A failed class constant never enters the class's public constant table, but its name stays
/// claimed so later lookups cannot fall through to inherited or global declarations. This record
/// is the payload behind the per-class lookup mask: scope and chain probes translate a hit into a
/// terminal blocked answer carrying only the declaration identity, never a fabricated value.
/// Records are produced exclusively by the skeleton const/enum joint pre-pass and become
/// immutable once published into `FrontendAnalysisData`.
///
/// @param declaration      the source `const` declaration that failed to become a usable constant
/// @param reason           human-readable failure reason collected from the const declaration owner
/// @param diagnosticAnchor smallest AST node that owns the root diagnostic (the offending
///                         expression node when evaluation reported one, else the declaration)
public record FrontendRejectedClassConstant(
        @NotNull VariableDeclaration declaration,
        @NotNull String reason,
        @NotNull Node diagnosticAnchor
) {
    public FrontendRejectedClassConstant {
        Objects.requireNonNull(declaration, "declaration must not be null");
        reason = StringUtil.requireNonBlank(reason, "reason");
        Objects.requireNonNull(diagnosticAnchor, "diagnosticAnchor must not be null");
    }
}
