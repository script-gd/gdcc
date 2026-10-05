package gd.script.gdcc.api.analysis;

import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Immutable per-module semantic snapshot published by `API.analyze(...)` (LSP foundation plan
/// §2.1). One module holds at most one latest snapshot; older snapshots are kept alive only by
/// caller references and stay self-consistent because everything reachable from here belongs to one
/// analysis generation.
///
/// Contents: the frozen (moduleGeneration, snapshotVersion) identity, source views (text shared by
/// reference with the frozen VFS request), the parsed AST generation, the shared-run semantic side
/// tables, the shared-run class registry, and the diagnostics of that shared run (remapped to
/// display paths; never containing compile-only `sema.compile_check` entries).
///
/// Immutability contract:
/// - collection components are defensively frozen at construction;
/// - the AST object graph is deeply immutable (gdparser freezes child collections at construction);
/// - the generation's CONTAINER TOPOLOGY is structurally frozen at snapshot construction:
///   `FrontendAnalysisData.freeze()` cascades over every side table and the provenance index
///   (all mutation channels, including views obtained before the freeze), and
///   `ClassRegistry.freeze()` closes class membership changes — any post-publication mutation
///   attempt throws (plan §2.1's enumerated `update*`/`applyPatch`/registry surface);
/// - model objects reachable INSIDE those containers are read by `FrontendSnapshotQueryService`
///   only through mutator-free views: `Scope` is wrapped by `ReadOnlyScope` (its
///   `setParentScope` throws), `ClassDef` is narrowed to its read-only interface, and query
///   results are DTO-only (no model object escapes the package); the structural freeze stays
///   as the second line of defense underneath (Phase 4 方案 C landing);
/// - query-time lazy indexes (e.g. the usages reverse index) live in the snapshot-owned
///   thread-safe memo and are never written back into the analysis data.
public final class ModuleAnalysisSnapshot {
    private final long moduleGeneration;
    private final long snapshotVersion;
    private final @NotNull String moduleId;
    private final @NotNull GodotVersion godotVersion;
    private final @NotNull Map<String, String> topLevelCanonicalNameMap;
    private final @NotNull List<SourceView> sourceViews;
    private final @NotNull DiagnosticSnapshot diagnostics;
    private final @NotNull FrontendModule module;
    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull ClassRegistry classRegistry;
    /// Publish-time query indexes (parent / range / line per unit), built once in the
    /// constructor so queries never rebuild tree walks (plan §2.4 "快照构建期索引").
    private final @NotNull SnapshotAstIndex astIndex;
    /// Snapshot-owned memo for lazily built query indexes (`ConcurrentHashMap` + single-compute),
    /// consumed by `FrontendSnapshotQueryService`. Never leaks into `FrontendAnalysisData`.
    private final @NotNull ConcurrentHashMap<String, Object> queryMemo = new ConcurrentHashMap<>();

    public ModuleAnalysisSnapshot(
            long moduleGeneration,
            long snapshotVersion,
            @NotNull String moduleId,
            @NotNull GodotVersion godotVersion,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull List<SourceView> sourceViews,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull FrontendModule module,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
        if (moduleGeneration <= 0) {
            throw new IllegalArgumentException("moduleGeneration must be positive");
        }
        if (snapshotVersion < 0) {
            throw new IllegalArgumentException("snapshotVersion must not be negative");
        }
        this.moduleGeneration = moduleGeneration;
        this.snapshotVersion = snapshotVersion;
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.godotVersion = Objects.requireNonNull(godotVersion, "godotVersion must not be null");
        this.topLevelCanonicalNameMap = Map.copyOf(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
        ));
        this.sourceViews = List.copyOf(Objects.requireNonNull(sourceViews, "sourceViews must not be null"));
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        this.module = Objects.requireNonNull(module, "module must not be null");
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        // Structural freeze (plan §2.1): the moment this payload becomes a snapshot, its whole
        // generation must be physically unwritable — package boundaries alone cannot stop a query
        // service from mutating live side tables or the registry.
        this.analysisData.freeze();
        this.classRegistry.freeze();
        this.astIndex = SnapshotAstIndex.build(this.sourceViews, this.module);
    }

    /// Module generation: distinguishes same-id modules across delete/recreate. Always compare
    /// together with `snapshotVersion()` when judging staleness.
    public long moduleGeneration() {
        return moduleGeneration;
    }

    /// Frozen module content version this snapshot was computed from.
    public long snapshotVersion() {
        return snapshotVersion;
    }

    public @NotNull String moduleId() {
        return moduleId;
    }

    public @NotNull GodotVersion godotVersion() {
        return godotVersion;
    }

    public @NotNull Map<String, String> topLevelCanonicalNameMap() {
        return topLevelCanonicalNameMap;
    }

    /// Per-unit source views in frozen request order, sharing source text by reference.
    public @NotNull List<SourceView> sourceViews() {
        return sourceViews;
    }

    /// Shared-run diagnostics (parse + sema), remapped to display paths; compile-only
    /// `sema.compile_check` entries never appear here.
    public @NotNull DiagnosticSnapshot diagnostics() {
        return diagnostics;
    }

    /// Same-package query-service access to this generation's AST. Individual nodes are exposed
    /// read-only to the public through `FrontendSnapshotQueryService.nodeAt` (the AST graph is
    /// deeply immutable); the module itself stays package-private so the side tables — the only
    /// place where node identity keys resolve — can never be queried across generations.
    @NotNull FrontendModule module() {
        return module;
    }

    /// Same-package query-service access to the shared-run side tables. Structurally frozen at
    /// snapshot construction: mutation attempts throw `IllegalStateException`.
    @NotNull FrontendAnalysisData analysisData() {
        return analysisData;
    }

    /// Same-package query-service access to the shared-run registry. Structurally frozen at
    /// snapshot construction (`addGdccClass` and friends throw `IllegalStateException`).
    @NotNull ClassRegistry classRegistry() {
        return classRegistry;
    }

    /// Snapshot-owned memo for lazily built query indexes; package-visible to the query service.
    @NotNull ConcurrentHashMap<String, Object> queryMemo() {
        return queryMemo;
    }

    /// Same-package query-service access to the publish-time AST indexes. Raw AST nodes never
    /// leave this package: the public query surface projects everything into DTOs.
    @NotNull SnapshotAstIndex astIndex() {
        return astIndex;
    }

    /// One unit's source view inside a snapshot.
    ///
    /// @param logicalPath the module-internal logical source path (as parsed)
    /// @param displayPath the caller-facing display path used by diagnostics
    /// @param source      the frozen source text (shared by reference, never copied)
    /// @param parseFailed whether this unit came from the parser's `parse.internal` recovery path;
    /// query services return empty results for such units
    public record SourceView(
            @NotNull String logicalPath,
            @NotNull String displayPath,
            @NotNull String source,
            boolean parseFailed
    ) {
        public SourceView {
            Objects.requireNonNull(logicalPath, "logicalPath must not be null");
            Objects.requireNonNull(displayPath, "displayPath must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }
}
