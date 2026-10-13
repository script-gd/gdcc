package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Per-source-unit query index built exactly once when a snapshot is published
/// (`frontend_lsp_foundation_implementation.md` §2.4). Holds three derived views over one
/// immutable AST generation:
///
/// - **parent index**: identity-keyed child → parent map, because gdparser AST nodes have no
///   parent pointers and ancestor walk-back is required by queries and (later) completion;
/// - **range index**: the preorder node list of the whole tree; `nodeAt` descends this tree by
///   range containment instead of rescanning, and `unitOf` checks membership by identity;
/// - **line index**: the UTF-8 byte offset of every line start, converting (0-based row,
///   0-based byte column) inputs to the canonical byte-offset form. gdparser ranges are UTF-8
///   byte spans and tree-sitter point columns are UTF-8 byte columns, so the index walks the
///   source text accumulating UTF-8 lengths rather than Java char counts.
///
/// Units flagged `parseFailed` carry a synthetic empty AST that queries must not expose; their
/// index answers every lookup with empty results.
final class AstUnitIndex {
    private final @NotNull ModuleAnalysisSnapshot.SourceView sourceView;
    private final @Nullable SourceFile ast;
    private final @NotNull IdentityHashMap<Node, Node> parentByNode;
    private final @NotNull List<Node> preorderNodes;
    private final int[] lineStartBytes;
    private final int totalBytes;

    private AstUnitIndex(
            @NotNull ModuleAnalysisSnapshot.SourceView sourceView,
            @Nullable SourceFile ast,
            @NotNull IdentityHashMap<Node, Node> parentByNode,
            @NotNull List<Node> preorderNodes,
            int @NotNull [] lineStartBytes,
            int totalBytes
    ) {
        this.sourceView = sourceView;
        this.ast = ast;
        this.parentByNode = parentByNode;
        this.preorderNodes = preorderNodes;
        this.lineStartBytes = lineStartBytes;
        this.totalBytes = totalBytes;
    }

    static @NotNull AstUnitIndex build(
            @NotNull ModuleAnalysisSnapshot.SourceView sourceView,
            @Nullable SourceFile ast
    ) {
        Objects.requireNonNull(sourceView, "sourceView must not be null");
        var parentByNode = new IdentityHashMap<Node, Node>();
        var preorderNodes = new ArrayList<Node>();
        // parseFailed units deliberately keep an empty index: queries over them return empty
        // results instead of exposing the parser's synthetic recovery AST.
        var effectiveAst = sourceView.parseFailed() ? null : ast;
        if (effectiveAst != null) {
            var pending = new ArrayDeque<Node>();
            pending.add(effectiveAst);
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                preorderNodes.add(node);
                // Push children in reverse so the queue drains in source (preorder) order.
                var children = node.getChildren();
                for (var i = children.size() - 1; i >= 0; i--) {
                    var child = children.get(i);
                    parentByNode.put(child, node);
                    pending.addFirst(child);
                }
            }
        }
        return new AstUnitIndex(
                sourceView,
                effectiveAst,
                parentByNode,
                List.copyOf(preorderNodes),
                buildLineStartBytes(sourceView.source()),
                utf8Length(sourceView.source())
        );
    }

    @NotNull String displayPath() {
        return sourceView.displayPath();
    }

    /// Total UTF-8 byte length of the unit source; offsets in `[0, totalBytes]` are valid
    /// cursor positions (the upper bound is the EOF position).
    int totalBytes() {
        return totalBytes;
    }

    @NotNull String logicalPath() {
        return sourceView.logicalPath();
    }

    /// Deepest node covering `byteOffset` under the half-open rule `[startByte, endByte)`.
    /// Zero-width ranges cover nothing and are never selected; among same-span candidates the
    /// descent guarantees the deepest one wins. Returns `null` for failed units and for offsets
    /// outside the root range.
    @Nullable Node nodeAt(int byteOffset) {
        if (byteOffset < 0) {
            throw new IllegalArgumentException("byteOffset must be >= 0, got " + byteOffset);
        }
        Node current = ast;
        if (current == null || !covers(current.range(), byteOffset)) {
            return null;
        }
        descent:
        while (true) {
            for (var child : current.getChildren()) {
                if (covers(child.range(), byteOffset)) {
                    current = child;
                    continue descent;
                }
            }
            return current;
        }
    }

    /// Converts a (0-based row, 0-based UTF-8 byte column) cursor to the canonical byte offset.
    /// Returns `null` when the row does not exist or the column runs past the line end, so a
    /// malformed coordinate can never silently resolve into the next line's nodes.
    @Nullable Integer byteOffsetAt(int row0, int column0) {
        if (row0 < 0 || column0 < 0) {
            throw new IllegalArgumentException("row/column must be >= 0, got " + row0 + "/" + column0);
        }
        if (row0 >= lineStartBytes.length) {
            return null;
        }
        var lineStart = lineStartBytes[row0];
        var lineEnd = row0 + 1 < lineStartBytes.length
                // The next line starts one byte after this line's `\n`.
                ? lineStartBytes[row0 + 1] - 1
                : totalBytes;
        // Compare against the line length first: adding a huge column to lineStart could
        // overflow into a small negative value and slip past the bounds check.
        if (column0 > lineEnd - lineStart) {
            return null;
        }
        return lineStart + column0;
    }

    /// Projects an AST range into the caller-facing form: display path + 1-based line/column
    /// (diagnostic parity) + raw byte span.
    @NotNull QuerySourceRange toQueryRange(@NotNull Range range) {
        return new QuerySourceRange(
                displayPath(),
                range.startByte(),
                range.endByte(),
                range.startPoint().row() + 1,
                range.startPoint().column() + 1,
                range.endPoint().row() + 1,
                range.endPoint().column() + 1
        );
    }

    /// Projects an ARBITRARY byte span (not necessarily an AST range) into the caller-facing
    /// form via the line index, clamped into `[0, sourceLength]`. Used when a completion
    /// context reports a range that does not map onto any AST node.
    @NotNull QuerySourceRange queryRangeAt(int startByte, int endByte) {
        var start = Math.clamp(startByte, 0, totalBytes);
        var end = Math.clamp(endByte, start, totalBytes);
        return new QuerySourceRange(
                displayPath(),
                start,
                end,
                lineOf(start) + 1,
                columnOf(start) + 1,
                lineOf(end) + 1,
                columnOf(end) + 1
        );
    }

    private int lineOf(int byteOffset) {
        var found = java.util.Arrays.binarySearch(lineStartBytes, byteOffset);
        // Without an exact match binarySearch returns -(insertionPoint) - 1; the line is the
        // greatest start not exceeding the offset.
        return found >= 0 ? found : Math.max(0, -found - 2);
    }

    private int columnOf(int byteOffset) {
        return byteOffset - lineStartBytes[lineOf(byteOffset)];
    }

    @Nullable Node parentOf(@NotNull Node node) {
        return parentByNode.get(node);
    }

    /// Whether `node` belongs to this unit's AST generation (identity semantics).
    boolean contains(@NotNull Node node) {
        return node == ast || parentByNode.containsKey(node);
    }

    /// Preorder view of the indexed tree (the range index); empty for failed units.
    @NotNull List<Node> preorderNodes() {
        return preorderNodes;
    }

    private static boolean covers(@NotNull Range range, int byteOffset) {
        return range.startByte() <= byteOffset && byteOffset < range.endByte();
    }

    private static int[] buildLineStartBytes(@NotNull String source) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        var utf8Offset = 0;
        for (var i = 0; i < source.length(); i++) {
            var ch = source.charAt(i);
            int charBytes;
            if (Character.isHighSurrogate(ch) && i + 1 < source.length() && Character.isLowSurrogate(source.charAt(i + 1))) {
                charBytes = 4;
                i++;
            } else {
                charBytes = utf8Length(ch);
            }
            utf8Offset += charBytes;
            // tree-sitter rows split on `\n` only; `\r` is ordinary content.
            if (ch == '\n') {
                starts.add(utf8Offset);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int utf8Length(@NotNull String source) {
        var total = 0;
        for (var i = 0; i < source.length(); i++) {
            var ch = source.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < source.length() && Character.isLowSurrogate(source.charAt(i + 1))) {
                total += 4;
                i++;
            } else {
                total += utf8Length(ch);
            }
        }
        return total;
    }

    private static int utf8Length(char ch) {
        if (ch <= 0x7F) {
            return 1;
        }
        if (ch <= 0x7FF) {
            return 2;
        }
        // Lone surrogates cannot appear in well-formed parsed source; encode as U+FFFD (3 bytes).
        return 3;
    }
}
