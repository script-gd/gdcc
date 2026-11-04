package gd.script.gdcc.cli;

import gd.script.gdcc.exception.GodotProjectException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/// Collects the project-mode source set: auto-discovered `.gd3` plus explicitly passed `.gd`
/// files, merged, deduplicated and sorted by project-relative path.
///
/// Auto-discovery mirrors the addon's `gdcc_file_reconciler.gd3` walk: dot directories are never
/// entered, per-file exclusion uses the `gdcc/sync/excluded_globs` patterns with Godot
/// `String.match` semantics (`*` crosses `/`; a wildcard-free directory pattern also excludes its
/// subtree via a `<glob>/*` probe). Two CLI-side hard boundaries the addon gets structurally:
/// a nested `project.godot` stops the walk from entering that subtree, and the managed areas
/// (`addons/gdcc/`, `.godot/`, `bin/`) are never collected. Source or directory symlinks are
/// refused rather than followed.
///
/// Explicit `.gd` arguments may override the exclusion globs (an explicit choice wins over a
/// pattern) but never the managed areas, and must resolve to readable regular files inside this
/// project — not inside a nested project and not outside the project root.
final class GodotProjectSourceCollector {
    private static final @NotNull String GD3_SUFFIX = ".gd3";
    private static final @NotNull String GD_SUFFIX = ".gd";
    private static final @NotNull String VIRTUAL_SOURCE_ROOT = "/src/";
    private static final @NotNull String RESOURCE_PREFIX = "res://";

    /// Managed areas that must never enter compilation: the GDCC addon itself, the Godot cache
    /// and the `bin/` publish destination. Applies to auto and explicit sources alike.
    private static final @NotNull List<String> FORBIDDEN_SOURCE_PREFIXES = List.of(
            "addons/gdcc/", ".godot/", "bin/"
    );

    /// One collected source before its content is read.
    ///
    /// @param hostPath     real path of the file on disk (also the `absolutePath` metadata arg).
    /// @param relativePath project-root-relative path with `/` separators.
    /// @param virtualPath  VFS path `/src/<relativePath>` (same mapping as `vfs_path()` in addon).
    /// @param displayPath  `res://<relativePath>` label used for diagnostics and source metadata.
    record ProjectSource(
            @NotNull Path hostPath,
            @NotNull String relativePath,
            @NotNull String virtualPath,
            @NotNull String displayPath
    ) {
    }

    /// A collected source plus its verified UTF-8 content.
    record PreparedSource(@NotNull ProjectSource source, @NotNull String content) {
    }

    /// Collects, deduplicates and sorts the merged source set. Fails with [GodotProjectException]
    /// before any module exists when the result would be empty or any explicit input is invalid.
    @NotNull List<ProjectSource> collect(@NotNull GodotProjectInput input, @NotNull List<Path> explicitFiles) {
        var collected = new TreeMap<String, ProjectSource>();
        collectAutoSources(input, collected);
        for (var argument : explicitFiles) {
            var source = explicitSource(input, argument);
            collected.putIfAbsent(source.relativePath(), source);
        }
        if (collected.isEmpty()) {
            throw new GodotProjectException(
                    "project " + input.projectRoot() + " has no sources to compile "
                            + "(no .gd3 discovered, no .gd given)"
            );
        }
        return List.copyOf(collected.values());
    }

    /// Reads every collected source as strict UTF-8. Collection-time validation is repeated here
    /// because the file may have been swapped meanwhile: each source must still resolve to the
    /// same regular, non-symlink host path, and a post-read fingerprint mismatch fails the whole
    /// preparation rather than silently compiling a mixed snapshot.
    @NotNull List<PreparedSource> readSources(@NotNull List<ProjectSource> sources) {
        var prepared = new ArrayList<PreparedSource>(sources.size());
        var fingerprints = new ArrayList<FileFingerprint>(sources.size());
        for (var source : sources) {
            fingerprints.add(fingerprint(source));
            prepared.add(new PreparedSource(source, readSource(source)));
        }
        for (var index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            if (!fingerprints.get(index).equals(fingerprint(source))) {
                throw new GodotProjectException("source changed during preparation: " + source.displayPath());
            }
        }
        return prepared;
    }

    /// Reads one verified source. `NOFOLLOW_LINKS` makes a post-collection symlink swap fail at
    /// open instead of silently reading the link target; a `toRealPath` drift check additionally
    /// catches swapped parent directories, which follow-link opens cannot see.
    private static @NotNull String readSource(@NotNull ProjectSource source) {
        try {
            if (!source.hostPath().toRealPath().equals(source.hostPath())) {
                throw new GodotProjectException(
                        "source path changed during preparation: " + source.displayPath());
            }
            final byte[] bytes;
            try (var in = Files.newInputStream(source.hostPath(), LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readAllBytes();
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (MalformedInputException | UnmappableCharacterException exception) {
            throw new GodotProjectException(
                    "source is not valid UTF-8: " + source.displayPath(), exception);
        } catch (IOException exception) {
            throw new GodotProjectException(
                    "cannot read source " + source.displayPath() + ": " + exception.getMessage(), exception);
        }
    }

    private void collectAutoSources(@NotNull GodotProjectInput input, @NotNull TreeMap<String, ProjectSource> collected) {
        var projectRoot = input.projectRoot();
        var globs = input.config().excludedGlobs();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(projectRoot)) {
                        return FileVisitResult.CONTINUE;
                    }
                    var name = dir.getFileName().toString();
                    // Dot directories (.godot, .git, …) carry no project sources.
                    if (name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // A nested project keeps its own compilation boundary.
                    if (Files.isRegularFile(dir.resolve(GodotProjectInput.PROJECT_FILE_NAME))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (isForbiddenTree(relativePath(projectRoot, dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    if (!file.getFileName().toString().endsWith(GD3_SUFFIX)) {
                        return FileVisitResult.CONTINUE;
                    }
                    var relative = relativePath(projectRoot, file);
                    if (isExcluded(relative, globs)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // walkFileTree is called without FOLLOW_LINKS: a symlinked file lands here as
                    // a link attribute, and symlinked dirs are never descended into.
                    if (attrs.isSymbolicLink()) {
                        throw new GodotProjectException("symbolic-link source refused: " + relative);
                    }
                    if (!attrs.isRegularFile()) {
                        throw new GodotProjectException("source is not a regular file: " + relative);
                    }
                    collected.put(relative, projectSource(file, relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exception) {
                    throw new GodotProjectException(
                            "cannot scan path " + file + ": " + exception.getMessage(), exception);
                }
            });
        } catch (IOException exception) {
            throw new GodotProjectException("cannot scan project tree: " + exception.getMessage(), exception);
        }
    }

    /// Validates one explicit `.gd` argument: must be a readable regular non-symlink file inside
    /// this project (nested-project files belong to that project), outside the managed areas.
    /// Glob exclusions do not apply — an explicit choice overrides the exclusion rules.
    private @NotNull ProjectSource explicitSource(@NotNull GodotProjectInput input, @NotNull Path argument) {
        if (argument.toString().isBlank()) {
            throw new GodotProjectException("explicit source path must not be blank");
        }
        var normalized = argument.toAbsolutePath().normalize();
        var fileName = normalized.getFileName();
        if (fileName == null || !fileName.toString().endsWith(GD_SUFFIX)) {
            throw new GodotProjectException("only .gd source files may accompany project.godot: " + argument);
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new GodotProjectException("symbolic-link source refused: " + argument);
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new GodotProjectException("explicit source is not a regular file: " + argument);
        }
        if (!Files.isReadable(normalized)) {
            throw new GodotProjectException("explicit source is not readable: " + argument);
        }

        final Path real;
        try {
            real = normalized.toRealPath();
        } catch (IOException exception) {
            throw new GodotProjectException("cannot resolve explicit source: " + argument, exception);
        }
        if (!real.startsWith(input.projectRoot())) {
            throw new GodotProjectException("explicit source must stay inside the project: " + argument);
        }
        var enclosingProject = GodotProjectInput.nearestEnclosingProjectFile(real.getParent());
        try {
            if (enclosingProject == null || !enclosingProject.toRealPath().equals(input.projectFile())) {
                throw new GodotProjectException(
                        "explicit source belongs to a different project (nested or external): " + argument);
            }
        } catch (IOException exception) {
            throw new GodotProjectException(
                    "cannot resolve enclosing project for " + argument, exception);
        }
        var relative = relativePath(input.projectRoot(), real);
        if (isForbiddenTree(relative)) {
            throw new GodotProjectException(
                    "explicit source must not live under the managed areas " + FORBIDDEN_SOURCE_PREFIXES
                            + ": " + relative);
        }
        return projectSource(real, relative);
    }

    private static @NotNull ProjectSource projectSource(@NotNull Path hostPath, @NotNull String relative) {
        return new ProjectSource(
                hostPath,
                relative,
                VIRTUAL_SOURCE_ROOT + relative,
                RESOURCE_PREFIX + relative
        );
    }

    private static @NotNull String relativePath(@NotNull Path projectRoot, @NotNull Path file) {
        var relative = projectRoot.relativize(file);
        var segments = new ArrayList<String>();
        for (var segment : relative) {
            segments.add(segment.toString());
        }
        return String.join("/", segments);
    }

    /// `relativePath` names either a directory (`"bin"`) or a file (`"bin/x.gd3"`): match the
    /// bare directory name too, or the managed subtree would be entered before any file check.
    private static boolean isForbiddenTree(@NotNull String relativePath) {
        for (var prefix : FORBIDDEN_SOURCE_PREFIXES) {
            if (relativePath.startsWith(prefix) || relativePath.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /// Per-file exclusion with Godot `String.match` semantics against the project-relative path:
    /// `*` matches any sequence including `/`, `?` one character, the match is whole-string and
    /// case-sensitive. Trailing `/` on a glob is stripped; a wildcard-free directory glob also
    /// excludes its whole subtree through the `<glob>/*` probe — the addon's exact contract.
    private static boolean isExcluded(@NotNull String relativePath, @NotNull List<String> globs) {
        for (var rawGlob : globs) {
            var glob = rawGlob;
            while (glob.endsWith("/")) {
                glob = glob.substring(0, glob.length() - 1);
            }
            if (glob.isEmpty()) {
                continue;
            }
            if (godotMatch(relativePath, glob) || godotMatch(relativePath, glob + "/*")) {
                return true;
            }
        }
        return false;
    }

    /// Wildcard match where `*` spans directories (unlike Java glob/nio matchers) and `?`
    /// consumes exactly one Unicode code point — Godot `String.match` counts characters, so a
    /// supplementary-character filename must not look like two units to the pattern.
    /// Backtracking two-pointer keeps it linear-time on the short relative paths involved.
    private static boolean godotMatch(@NotNull String text, @NotNull String pattern) {
        var textIndex = 0;
        var patternIndex = 0;
        var starPattern = -1;
        var starText = -1;
        while (textIndex < text.length()) {
            var patternCodePoint = patternIndex < pattern.length()
                    ? pattern.codePointAt(patternIndex) : -1;
            var textCodePoint = text.codePointAt(textIndex);
            // Godot's `?` matches any single character except `.`, unlike fnmatch's `?`.
            if ((patternCodePoint == '?' && textCodePoint != '.')
                    || patternCodePoint == textCodePoint) {
                textIndex += Character.charCount(textCodePoint);
                patternIndex += Character.charCount(patternCodePoint);
            } else if (patternCodePoint == '*') {
                starPattern = patternIndex++;
                starText = textIndex;
            } else if (starPattern != -1) {
                patternIndex = starPattern + 1;
                starText += Character.charCount(text.codePointAt(starText));
                textIndex = starText;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private static @NotNull FileFingerprint fingerprint(@NotNull ProjectSource source) {
        try {
            var attributes = Files.readAttributes(source.hostPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // Re-check what collection checked: a regular file swapped for a symlink or other
            // special file must fail here, before its (possibly outside-project) bytes are read.
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new GodotProjectException(
                        "source is no longer a regular file: " + source.displayPath());
            }
            return new FileFingerprint(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        } catch (IOException exception) {
            throw new GodotProjectException(
                    "cannot read source " + source.displayPath() + ": " + exception.getMessage(), exception);
        }
    }

    /// Size + timestamp + file identity captured before and after reading; a post-read mismatch
    /// means the file changed mid-preparation.
    private record FileFingerprint(long size, @NotNull FileTime modified, @Nullable Object fileKey) {
    }
}
