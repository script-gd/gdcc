package gd.script.gdcc.api.analysis;

import dev.superice.gdparser.frontend.ast.Node;
import gd.script.gdcc.api.API;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Shared fixtures for snapshot query tests: module analysis through the public API and
/// UTF-8-aware cursor offset computation (gdparser ranges are UTF-8 byte spans).
final class QueryTestSupport {
    private QueryTestSupport() {
    }

    /// Creates a module from (displayPath -> source) files, analyzes it and returns the
    /// published snapshot. The caller owns `api` and must close it.
    static @NotNull ModuleAnalysisSnapshot analyze(
            @NotNull API api,
            @NotNull String moduleId,
            @NotNull LinkedHashMap<String, String> files
    ) {
        return analyze(api, moduleId, files, Map.of());
    }

    /// `analyze` variant that also installs the project global class-name mapping (an external
    /// input the module state defaults to empty) before running analysis.
    static @NotNull ModuleAnalysisSnapshot analyze(
            @NotNull API api,
            @NotNull String moduleId,
            @NotNull LinkedHashMap<String, String> files,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        api.createModule(moduleId, "QueryTestModule");
        api.setTopLevelCanonicalNameMap(moduleId, topLevelCanonicalNameMap);
        files.forEach((path, source) -> api.putFile(moduleId, path, source));
        var result = api.analyze(moduleId);
        assertEquals(
                gd.script.gdcc.api.AnalysisResult.Outcome.COMPLETED,
                result.outcome(),
                () -> "analysis must complete, diagnostics: " + result.diagnostics().asList()
        );
        var snapshot = api.getLatestAnalysisSnapshot(moduleId);
        assertNotNull(snapshot, "a completed analysis must publish a snapshot");
        return snapshot;
    }

    /// UTF-8 byte offset of the `occurrence`-th (0-based) appearance of `token` in `source`.
    static int offset(@NotNull String source, @NotNull String token, int occurrence) {
        var fromIndex = 0;
        for (var i = 0; i <= occurrence; i++) {
            var charIndex = source.indexOf(token, fromIndex);
            assertTrue(charIndex >= 0, () -> "token not found: " + token + " occurrence " + occurrence);
            if (i == occurrence) {
                return source.substring(0, charIndex).getBytes(StandardCharsets.UTF_8).length;
            }
            fromIndex = charIndex + token.length();
        }
        throw new AssertionError("unreachable");
    }

    /// Offset of the middle of the `occurrence`-th token appearance — a cursor "inside" the
    /// token regardless of half-open edge rules.
    static int offsetInside(@NotNull String source, @NotNull String token, int occurrence) {
        return offset(source, token, occurrence) + token.getBytes(StandardCharsets.UTF_8).length / 2;
    }

    /// First node in DFS preorder matching `predicate`, or null.
    static @Nullable Node findNode(
            @NotNull Node root,
            @NotNull Predicate<Node> predicate
    ) {
        if (predicate.test(root)) {
            return root;
        }
        for (var child : root.getChildren()) {
            var found = findNode(child, predicate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SafeVarargs
    static @NotNull LinkedHashMap<String, String> files(@NotNull Map.Entry<String, String> @NotNull ... entries) {
        var files = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            files.put(entry.getKey(), entry.getValue());
        }
        return files;
    }

    static Map.@NotNull Entry<String, String> file(@NotNull String path, @NotNull String source) {
        return Map.entry(path, source);
    }
}
