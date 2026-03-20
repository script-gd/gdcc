package dev.superice.gdcc.frontend.diagnostic;

import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.Nullable;

/// Source span used by frontend diagnostics.
public record FrontendRange(
        int startByte,
        int endByte,
        FrontendPoint start,
        FrontendPoint end
) {
    public FrontendRange {
        if (startByte < 0) {
            throw new IllegalArgumentException("startByte must be >= 0, got " + startByte);
        }
        if (endByte < startByte) {
            throw new IllegalArgumentException("endByte must be >= startByte, got " + endByte);
        }
    }

    public static @Nullable FrontendRange fromAstRange(@Nullable Range range) {
        if (range == null) {
            return null;
        }
        return new FrontendRange(
                range.startByte(),
                range.endByte(),
                FrontendPoint.fromAstPoint(range.startPoint()),
                FrontendPoint.fromAstPoint(range.endPoint())
        );
    }
}
