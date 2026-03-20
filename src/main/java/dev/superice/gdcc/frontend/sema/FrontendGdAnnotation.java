package dev.superice.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Semantic-facing view of one parsed GDScript annotation.
///
/// The analyzer keeps the original argument expressions intact so later phases can decide
/// whether to interpret them structurally, stringify them, or diagnose unsupported shapes.
public record FrontendGdAnnotation(
        @NotNull String name,
        @NotNull List<Expression> arguments,
        @Nullable FrontendRange range
) {
    public FrontendGdAnnotation {
        Objects.requireNonNull(name, "name must not be null");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
    }

    public static @NotNull FrontendGdAnnotation fromAst(@NotNull AnnotationStatement annotationStatement) {
        Objects.requireNonNull(annotationStatement, "annotationStatement must not be null");
        return new FrontendGdAnnotation(
                annotationStatement.name(),
                annotationStatement.arguments(),
                FrontendRange.fromAstRange(annotationStatement.range())
        );
    }
}
