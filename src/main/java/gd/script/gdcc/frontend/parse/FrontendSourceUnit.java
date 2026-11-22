package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// One source unit and its tolerant parse result.
///
/// @param parseFailed whether the parser failed unexpectedly; the synthetic AST must not
///                    contribute a script class to the skeleton
/// @param parseRecoveries parser diagnostics in parse order, tied to this AST generation;
///                        empty on unexpected parser failure
public record FrontendSourceUnit(
        @NotNull Path path,
        @NotNull String source,
        @NotNull SourceFile ast,
        boolean parseFailed,
        @NotNull List<FrontendParseRecovery> parseRecoveries
) {
    public FrontendSourceUnit {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(ast, "ast must not be null");
        parseRecoveries = List.copyOf(Objects.requireNonNull(parseRecoveries, "parseRecoveries must not be null"));
        if (parseFailed && !parseRecoveries.isEmpty()) {
            throw new IllegalArgumentException("A failed parse cannot have parser recoveries");
        }
    }

    /// Convenience constructor for units produced by a successful (possibly error-tolerant) parse.
    public FrontendSourceUnit(@NotNull Path path, @NotNull String source, @NotNull SourceFile ast) {
        this(path, source, ast, false, List.of());
    }

    public FrontendSourceUnit(@NotNull Path path, @NotNull String source, @NotNull SourceFile ast, boolean parseFailed) {
        this(path, source, ast, parseFailed, List.of());
    }
}
