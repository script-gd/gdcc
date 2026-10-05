package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.api.DiagnosticSourcePathRemapper;
import gd.script.gdcc.frontend.parse.FrontendModule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Snapshot-level registry of per-unit query indexes (plan §2.4). Built once at snapshot
/// construction by pairing each `SourceView` with the same-generation `FrontendSourceUnit`
/// through the normalized logical-path key (the same key shape diagnostics remap with), so a
/// display-path query always lands on the AST generation it was computed from.
///
/// Package-private: indexes hand out raw AST nodes, which must never leave the query-service
/// package (the snapshot exposes the AST generation only to same-package consumers).
final class SnapshotAstIndex {
    private final @NotNull Map<String, AstUnitIndex> byLogicalPath;
    private final @NotNull Map<String, AstUnitIndex> byDisplayPath;
    private final @NotNull List<AstUnitIndex> all;
    /// Snapshot-level node → owning-unit identity map, filled from each unit's preorder walk at
    /// build time: usages-index construction resolves every fact site's unit through this
    /// table instead of scanning units per site (O(sites), not O(sites × units)).
    private final @NotNull IdentityHashMap<Node, AstUnitIndex> unitByNode;

    private SnapshotAstIndex(
            @NotNull Map<String, AstUnitIndex> byLogicalPath,
            @NotNull Map<String, AstUnitIndex> byDisplayPath,
            @NotNull List<AstUnitIndex> all,
            @NotNull IdentityHashMap<Node, AstUnitIndex> unitByNode
    ) {
        this.byLogicalPath = byLogicalPath;
        this.byDisplayPath = byDisplayPath;
        this.all = all;
        this.unitByNode = unitByNode;
    }

    static @NotNull SnapshotAstIndex build(
            @NotNull List<ModuleAnalysisSnapshot.SourceView> sourceViews,
            @NotNull FrontendModule module
    ) {
        Objects.requireNonNull(sourceViews, "sourceViews must not be null");
        Objects.requireNonNull(module, "module must not be null");
        var astByLogicalPath = new HashMap<String, dev.superice.gdparser.frontend.ast.SourceFile>();
        for (var unit : module.units()) {
            // The unit path and SourceView logical path normalize identically (see
            // DiagnosticSourcePathRemapper.logicalPathKey), so they join by text.
            astByLogicalPath.put(DiagnosticSourcePathRemapper.logicalPathKey(unit.path()), unit.ast());
        }
        var byLogicalPath = new HashMap<String, AstUnitIndex>();
        var byDisplayPath = new HashMap<String, AstUnitIndex>();
        var unitByNode = new IdentityHashMap<Node, AstUnitIndex>();
        for (var sourceView : sourceViews) {
            var index = AstUnitIndex.build(sourceView, astByLogicalPath.get(sourceView.logicalPath()));
            byLogicalPath.put(sourceView.logicalPath(), index);
            byDisplayPath.put(sourceView.displayPath(), index);
            for (var node : index.preorderNodes()) {
                unitByNode.put(node, index);
            }
        }
        return new SnapshotAstIndex(
                Map.copyOf(byLogicalPath),
                Map.copyOf(byDisplayPath),
                List.copyOf(byDisplayPath.values()),
                unitByNode
        );
    }

    /// Index for the unit a display path names, or `null` when the path is unknown to this
    /// snapshot (queries then return empty results rather than failing).
    @Nullable AstUnitIndex forDisplayPath(@NotNull String displayPath) {
        return byDisplayPath.get(displayPath);
    }

    /// Index for the unit a normalized logical-path key names (declaration-origin remapping).
    @Nullable AstUnitIndex forLogicalPath(@NotNull String logicalPath) {
        return byLogicalPath.get(logicalPath);
    }

    /// Finds the unit index owning `node` in this AST generation, or `null` when the node is
    /// foreign to the snapshot (identity keys from another generation must never resolve here).
    @Nullable AstUnitIndex unitOf(@NotNull Node node) {
        return unitByNode.get(node);
    }

    @NotNull List<AstUnitIndex> all() {
        return all;
    }
}
