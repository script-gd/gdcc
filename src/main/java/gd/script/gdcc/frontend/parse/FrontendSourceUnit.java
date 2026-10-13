package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/// One parsed source unit: source text + AST.
///
/// Parse diagnostics no longer live on the unit itself. They are reported directly into the
/// shared `DiagnosticManager`, and later phases consume those diagnostics via manager snapshots
/// published at explicit phase boundaries.
///
/// @param parseFailed whether this unit came from the parser's `parse.internal` recovery path
/// (unexpected parser runtime failure). Such units carry an empty synthetic AST plus their
/// already-reported `parse.internal` diagnostic; the class skeleton must exclude them so no
/// fictional top-level script class pollutes cross-file references.
public record FrontendSourceUnit(
        @NotNull Path path,
        @NotNull String source,
        @NotNull SourceFile ast,
        boolean parseFailed
) {
    public FrontendSourceUnit {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(ast, "ast must not be null");
    }

    /// Convenience constructor for units produced by a successful (possibly error-tolerant) parse.
    public FrontendSourceUnit(@NotNull Path path, @NotNull String source, @NotNull SourceFile ast) {
        this(path, source, ast, false);
    }
}
