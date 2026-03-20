package dev.superice.gdcc.frontend.diagnostic;

import dev.superice.gdparser.frontend.ast.Point;
import org.jetbrains.annotations.NotNull;

/// One-based source position used by GDCC diagnostics.
public record FrontendPoint(int line, int column) {
    public FrontendPoint {
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1, got " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be >= 1, got " + column);
        }
    }

    public static @NotNull FrontendPoint fromAstPoint(@NotNull Point point) {
        return new FrontendPoint(point.row() + 1, point.column() + 1);
    }
}
