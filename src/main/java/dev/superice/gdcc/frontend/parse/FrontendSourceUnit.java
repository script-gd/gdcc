package dev.superice.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Objects;

/// One parsed source unit: source text + AST.
///
/// Parse diagnostics no longer live on the unit itself. They are reported directly into the
/// shared `DiagnosticManager`, and later phases consume those diagnostics via manager snapshots
/// published at explicit phase boundaries.
public record FrontendSourceUnit(
        @NotNull Path path,
        @NotNull String source,
        @NotNull SourceFile ast
) {
    public FrontendSourceUnit {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(ast, "ast must not be null");
    }
}
