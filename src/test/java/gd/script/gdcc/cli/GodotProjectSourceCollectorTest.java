package gd.script.gdcc.cli;

import gd.script.gdcc.exception.GodotProjectException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodotProjectSourceCollectorTest {
    private final GodotProjectSourceCollector collector = new GodotProjectSourceCollector();

    @Test
    void autoDiscoversGd3SortedWithAddonPathMapping(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        writeFile(input.projectRoot().resolve("b/inner.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("a.gd3"), "extends RefCounted\n");
        // A .gd file is never auto-discovered, even when it is well-formed.
        writeFile(input.projectRoot().resolve("ignored.gd"), "extends RefCounted\n");

        var sources = collector.collect(input, List.of());

        assertEquals(List.of("a.gd3", "b/inner.gd3"), relativePaths(sources));
        var first = sources.getFirst();
        assertEquals("/src/a.gd3", first.virtualPath());
        assertEquals("res://a.gd3", first.displayPath());
        assertEquals(input.projectRoot().resolve("a.gd3"), first.hostPath());
        assertEquals("/src/b/inner.gd3", sources.get(1).virtualPath());
    }

    @Test
    void dotDirectoriesAreNeverEntered(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        writeFile(input.projectRoot().resolve("a.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve(".hidden/skip.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve(".godot/cache.gd3"), "extends RefCounted\n");

        assertEquals(List.of("a.gd3"), relativePaths(collector.collect(input, List.of())));
    }

    @Test
    void exclusionGlobsUseGodotMatchSemantics(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir, """
                [gdcc]
                sync/excluded_globs=PackedStringArray("addons/gdcc/*", "gen", "icons/*.gd3", "deep*")
                """);
        writeFile(input.projectRoot().resolve("keep.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("keep/deeper.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("addons/gdcc/tool.gd3"), "extends RefCounted\n");
        // `gen` has no wildcard: it excludes the `gen/` subtree but never the sibling `gen.gd3`.
        writeFile(input.projectRoot().resolve("gen/x/y.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("gen.gd3"), "extends RefCounted\n");
        // `*` crosses `/`: `icons/*.gd3` hides nested files too (Godot semantics).
        writeFile(input.projectRoot().resolve("icons/nested/x.gd3"), "extends RefCounted\n");
        // `deep*` matches both a directory glob and its subtree.
        writeFile(input.projectRoot().resolve("deep/a.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("deep2/a.gd3"), "extends RefCounted\n");

        assertEquals(List.of("gen.gd3", "keep.gd3", "keep/deeper.gd3"),
                relativePaths(collector.collect(input, List.of())));
    }

    @Test
    void nestedProjectSubtreeIsNotEntered(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        writeFile(input.projectRoot().resolve("a.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("nested/project.godot"), "config_version=5\n");
        writeFile(input.projectRoot().resolve("nested/inner.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("nested/deep/more.gd3"), "extends RefCounted\n");

        assertEquals(List.of("a.gd3"), relativePaths(collector.collect(input, List.of())));
    }

    @Test
    void explicitGdMergesIntoSameSortedSet(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        writeFile(input.projectRoot().resolve("z/auto.gd3"), "extends RefCounted\n");
        var helper = writeFile(input.projectRoot().resolve("src/helper.gd"), "extends RefCounted\n");

        var sources = collector.collect(input, List.of(helper));

        assertEquals(List.of("src/helper.gd", "z/auto.gd3"), relativePaths(sources));
        var explicit = sources.getFirst();
        assertEquals("/src/src/helper.gd", explicit.virtualPath());
        assertEquals("res://src/helper.gd", explicit.displayPath());
        assertEquals(helper.toRealPath(), explicit.hostPath());
    }

    @Test
    void explicitGdOverridesExclusionButNeverManagedAreas(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir, """
                [gdcc]
                sync/excluded_globs=PackedStringArray("excluded/*")
                """);
        var overridden = writeFile(input.projectRoot().resolve("excluded/helper.gd"), "extends RefCounted\n");
        var addon = writeFile(input.projectRoot().resolve("addons/gdcc/tool.gd"), "extends RefCounted\n");
        var cache = writeFile(input.projectRoot().resolve(".godot/cache.gd"), "extends RefCounted\n");
        var published = writeFile(input.projectRoot().resolve("bin/stale.gd"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("auto.gd3"), "extends RefCounted\n");

        var sources = collector.collect(input, List.of(overridden));

        // The glob-excluded .gd enters because it was explicitly named; managed areas do not.
        assertEquals(List.of("auto.gd3", "excluded/helper.gd"), relativePaths(sources));
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(addon)));
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(cache)));
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(published)));
    }

    @Test
    void managedAreasStayExcludedEvenWithEmptyUserGlobs(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir, """
                [gdcc]
                sync/excluded_globs=PackedStringArray()
                """);
        writeFile(input.projectRoot().resolve("keep.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("addons/gdcc/tool.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("bin/stale.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("bin/inner/deep.gd3"), "extends RefCounted\n");

        assertEquals(List.of("keep.gd3"), relativePaths(collector.collect(input, List.of())));
    }

    @Test
    void explicitGdMustStayInsideThisProject(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir.resolve("game"));
        writeFile(input.projectRoot().resolve("inner.gd3"), "extends RefCounted\n");
        var outside = writeFile(tempDir.resolve("outside.gd"), "extends RefCounted\n");
        var otherProject = project(tempDir.resolve("other"));
        var foreign = writeFile(otherProject.projectRoot().resolve("foreign.gd"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("nested/project.godot"), "config_version=5\n");
        var nested = writeFile(input.projectRoot().resolve("nested/in.gd"), "extends RefCounted\n");
        // Extra positional suffixes other than .gd are not accepted.
        var notGd = writeFile(input.projectRoot().resolve("script.gd3"), "extends RefCounted\n");
        var txt = writeFile(input.projectRoot().resolve("notes.txt"), "text\n");

        var outsideError = assertThrows(GodotProjectException.class,
                () -> collector.collect(input, List.of(outside)));
        assertTrue(outsideError.getMessage().contains("inside the project"), outsideError.getMessage());
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(foreign)));
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(nested)));
        var wrongSuffix = assertThrows(GodotProjectException.class,
                () -> collector.collect(input, List.of(notGd)));
        assertTrue(wrongSuffix.getMessage().contains("only .gd"), wrongSuffix.getMessage());
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(txt)));
        assertThrows(GodotProjectException.class,
                () -> collector.collect(input, List.of(input.projectRoot().resolve("missing.gd"))));
    }

    @Test
    void duplicateExplicitArgumentsAreDeduplicated(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        var helper = writeFile(input.projectRoot().resolve("helper.gd"), "extends RefCounted\n");
        var sameViaDotDot = input.projectRoot().resolve("sub/../helper.gd");

        var sources = collector.collect(input, List.of(helper, sameViaDotDot, helper));

        assertEquals(1, sources.size());
    }

    @Test
    void emptySourceSetIsRejected(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);

        var exception = assertThrows(GodotProjectException.class,
                () -> collector.collect(input, List.of()));
        assertTrue(exception.getMessage().contains("no sources to compile"), exception.getMessage());
    }

    @Test
    void symlinkedSourceAndDirectoryLinksAreRefusedOrIgnored(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        var real = writeFile(input.projectRoot().resolve("real.gd3"), "extends RefCounted\n");
        var link = input.projectRoot().resolve("link.gd3");
        try {
            Files.createSymbolicLink(link, real);
            Files.createSymbolicLink(input.projectRoot().resolve("dirlink"), input.projectRoot().resolve("sub"));
        } catch (UnsupportedOperationException | IOException exception) {
            return; // platform without symlink support: nothing to verify
        }
        writeFile(input.projectRoot().resolve("sub/deep.gd3"), "extends RefCounted\n");

        var linkError = assertThrows(GodotProjectException.class,
                () -> collector.collect(input, List.of()));
        assertTrue(linkError.getMessage().contains("symbolic-link"), linkError.getMessage());

        // The link dir is not traversed even when it contains a .gd3.
        Files.delete(link);
        assertEquals(List.of("real.gd3", "sub/deep.gd3"), relativePaths(collector.collect(input, List.of())));

        var explicitLink = input.projectRoot().resolve("explicit.gd");
        Files.createSymbolicLink(explicitLink, real);
        assertThrows(GodotProjectException.class, () -> collector.collect(input, List.of(explicitLink)));
    }

    @Test
    void questionMarkGlobMatchesOneCodePoint(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir, """
                [gdcc]
                sync/excluded_globs=PackedStringArray("?x.gd3", "??y.gd3")
                """);
        // `?` counts one Unicode code point (Godot String.match): the emoji is one code point,
        // so `??y.gd3` cannot match `😀y.gd3`; `abz.gd3` controls for a two-BMP-char name.
        writeFile(input.projectRoot().resolve("\uD83D\uDE00x.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("\uD83D\uDE00y.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("abz.gd3"), "extends RefCounted\n");

        assertEquals(List.of("abz.gd3", "\uD83D\uDE00y.gd3"),
                relativePaths(collector.collect(input, List.of())));
    }

    @Test
    void questionMarkGlobNeverMatchesDot(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir, """
                [gdcc]
                sync/excluded_globs=PackedStringArray("a?b.gd3")
                """);
        // Godot String.match: `?` matches any single character except '.'.
        writeFile(input.projectRoot().resolve("aXb.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("a.b.gd3"), "extends RefCounted\n");

        assertEquals(List.of("a.b.gd3"), relativePaths(collector.collect(input, List.of())));
    }

    @Test
    void sourceSwappedForSymlinkBeforeReadFails(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        var outside = writeFile(tempDir.resolve("outside-payload.gd3"), "extends RefCounted\n");
        writeFile(input.projectRoot().resolve("victim.gd3"), "extends RefCounted\n");

        var sources = collector.collect(input, List.of());
        try {
            Files.delete(input.projectRoot().resolve("victim.gd3"));
            Files.createSymbolicLink(input.projectRoot().resolve("victim.gd3"), outside);
        } catch (UnsupportedOperationException | IOException exception) {
            return; // platform without symlink support: nothing to verify
        }

        assertThrows(GodotProjectException.class, () -> collector.readSources(sources));
    }

    @Test
    void badUtf8SourceFailsDuringPreparation(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        var broken = input.projectRoot().resolve("bad.gd3");
        writeFile(input.projectRoot().resolve("ok.gd3"), "extends RefCounted\n");
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[]{'x', (byte) 0xFF, (byte) 0xFE});

        var sources = collector.collect(input, List.of());
        var exception = assertThrows(GodotProjectException.class, () -> collector.readSources(sources));
        assertTrue(exception.getMessage().contains("UTF-8"), exception.getMessage());
    }

    @Test
    void readSourcesReturnsContentForEverySource(@TempDir Path tempDir) throws IOException {
        var input = project(tempDir);
        writeFile(input.projectRoot().resolve("a.gd3"), "extends RefCounted\n");
        var helper = writeFile(input.projectRoot().resolve("helper.gd"), "extends RefCounted\n");

        var prepared = collector.readSources(collector.collect(input, List.of(helper)));

        assertEquals(2, prepared.size());
        assertEquals("extends RefCounted\n", prepared.get(0).content());
        assertEquals("res://a.gd3", prepared.get(0).source().displayPath());
        assertEquals("res://helper.gd", prepared.get(1).source().displayPath());
    }

    private static @NotNull List<String> relativePaths(
            @NotNull List<GodotProjectSourceCollector.ProjectSource> sources
    ) {
        return sources.stream().map(GodotProjectSourceCollector.ProjectSource::relativePath).toList();
    }

    private static GodotProjectInput project(Path root) throws IOException {
        return project(root, "");
    }

    private static GodotProjectInput project(Path root, String extra) throws IOException {
        var projectFile = root.resolve("project.godot");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile,
                "config_version=5\n\n[application]\nconfig/name=\"Game\"\n" + extra,
                StandardCharsets.UTF_8);
        return GodotProjectInput.load(projectFile);
    }

    private static Path writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
