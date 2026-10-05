package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Lazily built usages reverse index (plan §2.4 `usagesAt`). Every site recorded in the three
/// fact tables (`symbolBindings` / `resolvedMembers` / `resolvedCalls`) is FIRST normalized to
/// its stable source declaration identity and only then grouped — so a bare identifier binding
/// and a `self.x` member access to the same property land in one group.
///
/// Grouping rules:
/// - the grouping key is the normalized AST declaration node (identity semantics);
/// - collection-shaped provenance counts the site toward EVERY normalizable element;
/// - member/call sites enter the index only when their status is `RESOLVED` or `BLOCKED`
///   (a BLOCKED site keeps its blocked-winner provenance as a real usage intent);
///   `DEFERRED`/`UNSUPPORTED`/`FAILED`/`DYNAMIC` sites are absent BY STATUS — their
///   declaration payloads (e.g. the receiver class recorded for diagnostics on a failed
///   `ClassName.member` access) are not usage intent;
/// - binding sites are absent only when normalization fails (`null` provenance, external
///   declarations, or collections whose elements all failed); type-position references are
///   filtered by the `TYPE_META` kind. Together with the member/call status filter this
///   covers the documented absent sets (unbound identifiers, `DEFERRED`/`UNSUPPORTED`
///   sites, type positions).
///
/// The index lives in the snapshot-owned thread-safe memo (`ConcurrentHashMap` + single
/// compute) and is never written back into `FrontendAnalysisData`.
final class UsagesReverseIndex {
    /// Memo key inside `ModuleAnalysisSnapshot.queryMemo()`.
    static final String MEMO_KEY = "usagesReverseIndex";

    /// Total-order site ordering shared with cross-group unions: same-start sites tie-break by
    /// span end, then by position, so output is deterministic across builds.
    static final java.util.Comparator<QuerySourceRange> SITE_ORDER = java.util.Comparator
            .comparing(QuerySourceRange::displayPath)
            .thenComparingInt(QuerySourceRange::startByte)
            .thenComparingInt(QuerySourceRange::endByte)
            .thenComparingInt(QuerySourceRange::startLine)
            .thenComparingInt(QuerySourceRange::startColumn);

    private UsagesReverseIndex() {
    }

    @SuppressWarnings("unchecked")
    static @NotNull IdentityHashMap<Node, List<QuerySourceRange>> require(@NotNull ModuleAnalysisSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return (IdentityHashMap<Node, List<QuerySourceRange>>) snapshot.queryMemo()
                .computeIfAbsent(MEMO_KEY, _ -> build(snapshot));
    }

    private static @NotNull IdentityHashMap<Node, List<QuerySourceRange>> build(@NotNull ModuleAnalysisSnapshot snapshot) {
        var analysisData = snapshot.analysisData();
        var siteLists = new IdentityHashMap<Node, List<QuerySourceRange>>();

        analysisData.symbolBindings().forEach((siteNode, binding) -> {
            // Type-position references never enter usages (documented absent set); SELF/LITERAL
            // and friends carry no declaration provenance and drop out in normalization anyway.
            if (binding.kind() == FrontendBindingKind.TYPE_META) {
                return;
            }
            recordSites(snapshot, siteLists, siteNode, binding.declarationSite());
        });
        analysisData.resolvedMembers().forEach((siteNode, member) -> {
            if (member.status() != FrontendMemberResolutionStatus.RESOLVED
                    && member.status() != FrontendMemberResolutionStatus.BLOCKED) {
                return;
            }
            recordSites(snapshot, siteLists, siteNode, member.declarationSite());
        });
        analysisData.resolvedCalls().forEach((siteNode, call) -> {
            if (call.status() != FrontendCallResolutionStatus.RESOLVED
                    && call.status() != FrontendCallResolutionStatus.BLOCKED) {
                return;
            }
            recordSites(snapshot, siteLists, siteNode, call.declarationSite());
        });

        // Deterministic output: dedupe (a node may hold more than one fact kind) and sort.
        var result = new IdentityHashMap<Node, List<QuerySourceRange>>();
        siteLists.forEach((declaration, sites) -> {
            var deduped = new LinkedHashSet<>(sites);
            var sorted = deduped.stream().sorted(SITE_ORDER).toList();
            result.put(declaration, sorted);
        });
        return result;
    }

    private static void recordSites(
            @NotNull ModuleAnalysisSnapshot snapshot,
            @NotNull IdentityHashMap<Node, List<QuerySourceRange>> siteLists,
            @NotNull Node siteNode,
            Object declarationSite
    ) {
        var normalized = DeclarationNormalizer.normalize(declarationSite, snapshot);
        if (normalized.outcome() != DeclarationNormalizer.Outcome.NORMALIZED) {
            return;
        }
        var siteUnit = snapshot.astIndex().unitOf(siteNode);
        if (siteUnit == null) {
            return;
        }
        var siteRange = siteUnit.toQueryRange(siteNode.range());
        for (var declarationNode : normalized.sourceNodes()) {
            siteLists.computeIfAbsent(declarationNode, _ -> new ArrayList<>()).add(siteRange);
        }
    }
}
