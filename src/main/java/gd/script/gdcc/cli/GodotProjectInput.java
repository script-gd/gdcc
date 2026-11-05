package gd.script.gdcc.cli;

import gd.script.gdcc.exception.GodotConfigException;
import gd.script.gdcc.exception.GodotProjectException;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/// A validated project-mode CLI entry: the `project.godot` positional argument plus its parsed
/// GDCC settings. Loading resolves the project file to its real path (so symlink-spelled
/// invocations land on one canonical root) and parses/validates settings eagerly — everything the
/// build consumes must be known-good before a module is created.
///
/// The project identity mirrors the addon's derivation: a filename-safe form of the project name
/// plus a hash of the real project path and name, giving a stable per-project directory under
/// `.godot/gdcc/cli/<identity>` that cannot collide between same-named projects.
record GodotProjectInput(
        @NotNull Path projectFile,
        @NotNull Path projectRoot,
        @NotNull Path lexicalProjectRoot,
        @NotNull GodotProjectConfig config
) {
    static final @NotNull String PROJECT_FILE_NAME = "project.godot";

    /// Maximum sanitized project-name characters kept inside the identity (mirrors the addon's
    /// `.left(32)` cap so identities stay bounded but human-recognizable).
    private static final int MAX_IDENTITY_NAME_LENGTH = 32;

    /// Loads and validates the project file + GDCC settings; fails before any module exists.
    static @NotNull GodotProjectInput load(@NotNull Path projectArgument) {
        if (projectArgument.toString().isBlank()) {
            throw new GodotProjectException("project.godot path must not be blank");
        }
        var normalized = projectArgument.toAbsolutePath().normalize();
        var fileName = normalized.getFileName();
        if (fileName == null || !fileName.toString().equals(PROJECT_FILE_NAME)) {
            throw new GodotProjectException("project input must point to a project.godot file: " + projectArgument);
        }
        if (Files.notExists(normalized)) {
            throw new GodotProjectException("project.godot file does not exist: " + projectArgument);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new GodotProjectException("project.godot path is not a regular file: " + projectArgument);
        }

        final Path projectFile;
        try {
            projectFile = normalized.toRealPath();
        } catch (IOException exception) {
            throw new GodotProjectException("cannot resolve project.godot path: " + projectArgument, exception);
        }

        var origin = normalized.toString();
        final String text;
        try {
            text = Files.readString(normalized, StandardCharsets.UTF_8);
        } catch (MalformedInputException exception) {
            throw new GodotConfigException(origin + ": file is not valid UTF-8", exception);
        } catch (IOException exception) {
            throw new GodotConfigException(origin + ": cannot read project file: " + exception.getMessage(), exception);
        }
        var config = GodotProjectConfig.from(GodotConfigFile.parse(text, origin), origin);

        var lexicalRoot = normalized.getParent() != null
                ? normalized.getParent()
                : normalized;
        return new GodotProjectInput(
                projectFile,
                projectFile.getParent() != null ? projectFile.getParent() : projectFile,
                lexicalRoot,
                config
        );
    }

    /// Stable identity used for the default work directory and as the publish-facing project tag.
    /// `safeName_sha256(realRoot\nprojectName)[0:16]` — name keeps it readable, hash keeps
    /// same-named projects distinct, matching the addon's `_compile_module_id` formula shape.
    @NotNull String identity() {
        var identityText = projectRoot + "\n" + config.projectName();
        var hash = StringUtil.toHex(StringUtil.sha256(identityText.getBytes(StandardCharsets.UTF_8)), 8);
        return safeFileName(config.projectName()) + "_" + hash;
    }

    /// `<root>/.godot/gdcc/cli` — the only location inside the project an explicit `-o` may point.
    @NotNull Path cliCacheRoot() {
        return projectRoot.resolve(".godot").resolve("gdcc").resolve("cli");
    }

    @NotNull Path defaultWorkDirectory() {
        return cliCacheRoot().resolve(identity());
    }

    /// Resolves the project-mode work directory: the default cache subdirectory, or an explicit
    /// `-o` that must stay (a) under the CLI cache subtree, or (b) outside the project, not an
    /// ancestor of it, and not inside another project's resource tree (dot-directory subtrees of
    /// other projects — cache areas — are not resource trees and stay allowed).
    @NotNull Path workDirectory(@Nullable Path output) {
        if (output == null) {
            validateWorkDirectory(defaultWorkDirectory());
            return defaultWorkDirectory();
        }
        if (output.toString().isBlank()) {
            throw new IllegalArgumentException("Output path must not be blank");
        }
        var resolved = output.toAbsolutePath().normalize();
        validateWorkDirectory(resolved);
        return resolved;
    }

    /// Validates one work-directory candidate on both its spelled form and its real
    /// destination: an external symlink must not redirect the build into a resource tree or a
    /// project ancestor, and no existing component may be a regular file (which would otherwise
    /// surface as a late compile failure after module creation).
    private void validateWorkDirectory(@NotNull Path resolved) {
        // Check both the real and the spelled root: paths written through a symlinked project
        // root share the resource tree even though only one spelling matches `startsWith`.
        var roots = projectRoot.equals(lexicalProjectRoot)
                ? List.of(projectRoot)
                : List.of(projectRoot, lexicalProjectRoot);
        var realDestination = realDestination(resolved);
        var candidates = resolved.equals(realDestination)
                ? List.of(resolved)
                : List.of(resolved, realDestination);
        for (var candidate : candidates) {
            for (var root : roots) {
                var cacheSubtree = root.resolve(".godot").resolve("gdcc").resolve("cli");
                if (candidate.startsWith(root) && !candidate.startsWith(cacheSubtree)) {
                    throw new GodotProjectException(
                            "-o output must stay outside the project resource tree "
                                    + "(only .godot/gdcc/cli is allowed): " + resolved
                    );
                }
                if (root.startsWith(candidate)) {
                    throw new GodotProjectException(
                            "-o output must not be an ancestor directory of the project: " + resolved
                    );
                }
            }
            var enclosingProject = nearestEnclosingProjectFile(candidate);
            if (enclosingProject != null
                    && insideResourceTree(candidate, enclosingProject.getParent())) {
                throw new GodotProjectException(
                        "-o output must not fall inside another Godot project: " + resolved
                );
            }
        }
    }

    /// The filesystem destination `resolved` would actually occupy: the deepest existing
    /// ancestor resolved to its real path (catching redirecting symlinks) plus the not-yet-
    /// existing suffix. An existing component that is not a directory can never become the
    /// work directory, so it is rejected here instead of inside the compile task.
    private static @NotNull Path realDestination(@NotNull Path resolved) {
        var deepestExisting = resolved;
        // NOFOLLOW existence matters: a dangling symlink exists as a directory entry, and the
        // isDirectory check below must reject it instead of climbing above it.
        while (deepestExisting != null && !Files.exists(deepestExisting, LinkOption.NOFOLLOW_LINKS)) {
            deepestExisting = deepestExisting.getParent();
        }
        if (deepestExisting == null) {
            return resolved;
        }
        if (!Files.isDirectory(deepestExisting)) {
            throw new GodotProjectException(
                    "-o output path collides with an existing non-directory component: " + resolved
            );
        }
        final Path realAncestor;
        try {
            realAncestor = deepestExisting.toRealPath();
        } catch (IOException exception) {
            throw new GodotProjectException("cannot resolve -o output path: " + resolved, exception);
        }
        return realAncestor.resolve(deepestExisting.relativize(resolved)).normalize();
    }

    /// A path sits inside `projectRoot`'s resource tree unless a `.`-prefixed segment places it in
    /// a dot directory (Godot's filesystem ignores those; `.godot` is the engine cache).
    private static boolean insideResourceTree(@NotNull Path candidate, @NotNull Path projectRoot) {
        var relative = projectRoot.relativize(candidate);
        for (var segment : relative) {
            if (segment.toString().startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    /// Nearest `project.godot` at or above `directory` (which need not exist yet — the walk checks
    /// ancestor directories for the marker file), or null when none exists. Used both for `-o`
    /// boundary checks and to prove an explicit `.gd` belongs to this project.
    static @Nullable Path nearestEnclosingProjectFile(@NotNull Path directory) {
        for (Path current = directory.toAbsolutePath().normalize();
             current != null;
             current = current.getParent()) {
            var candidate = current.resolve(PROJECT_FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /// Filename-safe project name: anything outside `[A-Za-z0-9._-]` becomes `_`, runs collapse,
    /// result capped and never empty. Looser than Godot's `validate_filename` on purpose — this is
    /// an internal directory name, not a user-facing artifact name.
    private static @NotNull String safeFileName(@NotNull String projectName) {
        var sanitized = new StringBuilder();
        var lastWasUnderscore = false;
        for (var c : projectName.strip().toCharArray()) {
            var allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (allowed) {
                sanitized.append(c);
                lastWasUnderscore = false;
            } else if (!lastWasUnderscore) {
                sanitized.append('_');
                lastWasUnderscore = true;
            }
            if (sanitized.length() >= MAX_IDENTITY_NAME_LENGTH) {
                break;
            }
        }
        var result = sanitized.toString();
        return result.isEmpty() ? "project" : result;
    }
}
