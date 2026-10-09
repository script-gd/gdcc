package gd.script.gdcc.api.analysis;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Caller-facing source location returned by snapshot queries
/// (`frontend_lsp_foundation_implementation.md` §2.4 coordinate contract): the display path plus
/// a 1-based line/column span (matching diagnostic
/// conventions) plus the raw byte span of the underlying AST range.
///
/// Byte offsets follow gdparser semantics (UTF-8 bytes into the source); the line/column fields
/// are the AST point shifted to 1-based. UTF-16 column conversion for LSP wire format is the
/// future adapter layer's job, not part of this record.
public record QuerySourceRange(
        @NotNull String displayPath,
        int startByte,
        int endByte,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn
) {
    public QuerySourceRange {
        Objects.requireNonNull(displayPath, "displayPath must not be null");
        if (startByte < 0) {
            throw new IllegalArgumentException("startByte must be >= 0, got " + startByte);
        }
        if (endByte < startByte) {
            throw new IllegalArgumentException("endByte must be >= startByte, got " + endByte);
        }
        if (startLine < 1 || endLine < 1 || startColumn < 1 || endColumn < 1) {
            throw new IllegalArgumentException("line/column components must be 1-based (>= 1)");
        }
    }
}
