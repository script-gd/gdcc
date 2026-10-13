package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.Expression;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Semantic-facing view of one parsed GDScript annotation.
///
/// The analyzer keeps the original argument expressions intact so later phases can decide
/// whether to interpret them structurally, stringify them, or diagnose unsupported shapes.
///
/// @param sourceStatement the AST statement this projection was built from; the annotation-usage
///                        checker consults it against `skippedSubtreeRoots()` so a damaged
///                        annotation keeps only its parser diagnostic (single-owner recovery rule)
public record FrontendGdAnnotation(
        @NotNull String name,
        @NotNull List<Expression> arguments,
        @Nullable FrontendRange range,
        @Nullable AnnotationStatement sourceStatement
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
                FrontendRange.fromAstRange(annotationStatement.range()),
                annotationStatement
        );
    }
}
