package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.api.DiagnosticSourcePathRemapper;
import gd.script.gdcc.scope.GdScriptClassConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// Declaration-source normalization shared by `definitionAt`, `usagesAt` and the GDCC branch of
/// `documentationAt` (`frontend_lsp_foundation_implementation.md` §2.4). Published
/// `declarationSite` payloads arrive in three shapes:
///
/// - AST nodes — used directly (already a stable source identity);
/// - single declaration model objects (`PropertyDef`, `LirFunctionDef`, ...) — resolved through
///   the skeleton-published `declarationOrigins` provenance index (model identity → AST
///   declaration node + logical path);
/// - collection-shaped provenance (e.g. overload `List<? extends FunctionDef>`) — normalized
///   element by element: every normalizable element contributes its source node; elements that
///   fail are counted, never silently dropped and never cause the others to be discarded.
///
/// A normalized AST node is the stable source declaration identity for usages grouping; the
/// owning unit is resolved by identity inside the snapshot so cross-generation nodes can never
/// leak into another snapshot's tables.
final class DeclarationNormalizer {
    private DeclarationNormalizer() {
    }

    enum Outcome {
        /// `declarationSite` was `null` — unbound or provenance-free fact.
        NO_SITE,
        /// At least one source declaration was normalized.
        NORMALIZED,
        /// Everything failed to normalize (external engine/builtin metadata, or a collection
        /// whose elements all failed) — the declaration exists but has no source position.
        ALL_EXTERNAL
    }

    /// @param elements             normalized AST declaration nodes in provenance order (identity-stable);
    /// a `null` entry explicitly marks an element that could not be normalized — its position
    /// in the sequence is preserved so callers never silently drop or reorder candidates
    /// @param collectionProvenance whether the site was collection-shaped
    record Result(
            @NotNull Outcome outcome,
            @NotNull List<@Nullable Node> elements,
            boolean collectionProvenance
    ) {
        Result {
            Objects.requireNonNull(outcome, "outcome must not be null");
            // Element slots may be null (explicit un-localizable markers), so List.copyOf —
            // which rejects nulls — cannot be used here.
            elements = java.util.Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(elements, "elements must not be null"))
            );
        }

        /// The normalizable declaration nodes in provenance order.
        @NotNull List<Node> sourceNodes() {
            return elements.stream().filter(Objects::nonNull).toList();
        }

        /// Elements that failed to normalize (explicitly reported as "no source position").
        int unlocalizableCount() {
            return (int) elements.stream().filter(Objects::isNull).count();
        }
    }

    static @NotNull Result normalize(@Nullable Object declarationSite, @NotNull ModuleAnalysisSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        switch (declarationSite) {
            case null -> {
                return new Result(Outcome.NO_SITE, List.of(), false);
            }
            case Node node -> {
                return new Result(Outcome.NORMALIZED, List.of(node), false);
            }
            case Collection<?> collection -> {
                var elements = new ArrayList<Node>(collection.size());
                var anyNormalized = false;
                for (var element : collection) {
                    var normalized = normalizeSingle(element, snapshot);
                    if (normalized == null) {
                        elements.add(null);
                    } else {
                        elements.add(normalized);
                        anyNormalized = true;
                    }
                }
                var outcome = anyNormalized ? Outcome.NORMALIZED : Outcome.ALL_EXTERNAL;
                return new Result(outcome, elements, true);
            }
            default -> {
            }
        }
        var normalized = normalizeSingle(declarationSite, snapshot);
        return normalized == null
                ? new Result(Outcome.ALL_EXTERNAL, java.util.Collections.singletonList(null), false)
                : new Result(Outcome.NORMALIZED, List.of(normalized), false);
    }

    /// Single non-AST payload normalizes only through the provenance index; external metadata
    /// (engine/builtin models never recorded by the skeleton) resolves to `null`.
    /// `GdScriptClassConstant` wrappers are unwrapped to their enum declaration first: the
    /// skeleton records provenance for `GdScriptEnumConstant`/`GdScriptEnumGroup`, not for the
    /// carrier itself, and the unwrapped node is the same source identity direct bindings use.
    private static @Nullable Node normalizeSingle(@Nullable Object element, @NotNull ModuleAnalysisSnapshot snapshot) {
        if (element == null) {
            return null;
        }
        if (element instanceof Node node) {
            return node;
        }
        var origin = snapshot.analysisData().declarationOrigins().get(element);
        if (origin != null) {
            return origin.declarationNode();
        }
        if (element instanceof GdScriptClassConstant classConstant) {
            var unwrapped = snapshot.analysisData().declarationOrigins().get(classConstant.declaration());
            return unwrapped == null ? null : unwrapped.declarationNode();
        }
        return null;
    }

    /// Projects one normalized declaration node to its caller-facing location by resolving the
    /// owning unit inside the snapshot. Returns `null` when the node is foreign to this
    /// snapshot's AST generation (defensive; published provenance is always same-generation).
    static @Nullable QuerySourceRange locate(@NotNull Node declarationNode, @NotNull ModuleAnalysisSnapshot snapshot) {
        var unitIndex = snapshot.astIndex().unitOf(declarationNode);
        return unitIndex == null ? null : unitIndex.toQueryRange(declarationNode.range());
    }

    /// Resolves the unit index a provenance record points at, using the same normalized
    /// logical-path key shape diagnostics remap with.
    static @Nullable AstUnitIndex unitOfLogicalPath(@NotNull java.nio.file.Path logicalPath, @NotNull ModuleAnalysisSnapshot snapshot) {
        return snapshot.astIndex().forLogicalPath(DiagnosticSourcePathRemapper.logicalPathKey(logicalPath));
    }
}
