package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiDirectoryNotEmptyException;
import dev.superice.gdcc.exception.ApiEntryTypeMismatchException;
import dev.superice.gdcc.exception.ApiPathNotFoundException;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ModuleState {
    private final @NotNull String moduleId;
    private final @NotNull String moduleName;
    private final @NotNull Clock clock;
    private final @NotNull DirectoryNode root;
    private final @NotNull CompileOptions compileOptions;
    private final @NotNull Map<String, String> topLevelCanonicalNameMap;
    private final boolean hasLastCompileResult;

    ModuleState(@NotNull String moduleId, @NotNull String moduleName, @NotNull Clock clock) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        root = new DirectoryNode();
        compileOptions = CompileOptions.defaults();
        topLevelCanonicalNameMap = Map.of();
        hasLastCompileResult = false;
    }

    @NotNull
    synchronized ModuleSnapshot snapshot() {
        return new ModuleSnapshot(
                moduleId,
                moduleName,
                compileOptions,
                topLevelCanonicalNameMap,
                hasLastCompileResult,
                root.childCount()
        );
    }

    /// `createDirectory(...)` is idempotent for existing directories and creates missing ancestors.
    synchronized @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot createDirectory(@NotNull VirtualPath path) {
        if (path.isRoot()) {
            return root.snapshot(path);
        }
        var current = root;
        for (var i = 0; i < path.segments().size(); i++) {
            var segment = path.segments().get(i);
            var existing = current.child(segment);
            if (existing == null) {
                var created = new DirectoryNode();
                current.putChild(segment, created);
                current = created;
                continue;
            }
            var currentPath = path.prefixText(i + 1);
            current = switch (existing) {
                case DirectoryNode directoryNode -> directoryNode;
                case FileNode _ -> throw typeMismatch(
                        currentPath,
                        VfsEntrySnapshot.Kind.FILE,
                        VfsEntrySnapshot.Kind.DIRECTORY
                );
            };
        }
        return current.snapshot(path);
    }

    /// `putFile(...)` owns the "mkdir -p parent directories" behavior promised by the RPC plan.
    synchronized @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull VirtualPath path,
            @NotNull String content
    ) {
        if (path.isRoot()) {
            throw new IllegalArgumentException("path '/' cannot be used as a file path");
        }
        Objects.requireNonNull(content, "content must not be null");
        var parent = requireParentDirectory(path, true);
        var existing = parent.child(path.name());
        if (existing instanceof DirectoryNode) {
            throw typeMismatch(path.text(), VfsEntrySnapshot.Kind.DIRECTORY, VfsEntrySnapshot.Kind.FILE);
        }
        var fileNode = FileNode.fromContent(content, clock);
        parent.putChild(path.name(), fileNode);
        return fileNode.snapshot(path);
    }

    synchronized @NotNull String readFile(@NotNull VirtualPath path) {
        return switch (requireNode(path)) {
            case FileNode fileNode -> fileNode.content();
            case DirectoryNode _ -> throw typeMismatch(
                    path.text(),
                    VfsEntrySnapshot.Kind.DIRECTORY,
                    VfsEntrySnapshot.Kind.FILE
            );
        };
    }

    synchronized @NotNull VfsEntrySnapshot deletePath(@NotNull VirtualPath path, boolean recursive) {
        if (path.isRoot()) {
            throw new IllegalArgumentException("path '/' cannot be deleted; delete the module instead");
        }
        var parent = requireParentDirectory(path, false);
        var existing = parent.child(path.name());
        if (existing == null) {
            throw pathNotFound(path.text());
        }
        if (!recursive && existing instanceof DirectoryNode directoryNode && directoryNode.hasChildren()) {
            throw new ApiDirectoryNotEmptyException(
                    "Directory '" + path.text() + "' in module '" + moduleId + "' is not empty; recursive delete required"
            );
        }
        parent.removeChild(path.name());
        return existing.snapshot(path);
    }

    synchronized @NotNull List<VfsEntrySnapshot> listDirectory(@NotNull VirtualPath path) {
        return requireDirectory(path).listChildren(path);
    }

    synchronized @NotNull VfsEntrySnapshot readEntry(@NotNull VirtualPath path) {
        return requireNode(path).snapshot(path);
    }

    private @NotNull DirectoryNode requireParentDirectory(@NotNull VirtualPath path, boolean createMissing) {
        var current = root;
        for (var i = 0; i < path.segments().size() - 1; i++) {
            var segment = path.segments().get(i);
            var existing = current.child(segment);
            if (existing == null) {
                if (!createMissing) {
                    throw pathNotFound(path.text());
                }
                var created = new DirectoryNode();
                current.putChild(segment, created);
                current = created;
                continue;
            }
            var currentPath = path.prefixText(i + 1);
            current = switch (existing) {
                case DirectoryNode directoryNode -> directoryNode;
                case FileNode _ -> throw typeMismatch(
                        currentPath,
                        VfsEntrySnapshot.Kind.FILE,
                        VfsEntrySnapshot.Kind.DIRECTORY
                );
            };
        }
        return current;
    }

    private @NotNull VfsNode requireNode(@NotNull VirtualPath path) {
        if (path.isRoot()) {
            return root;
        }
        var current = root;
        for (var i = 0; i < path.segments().size(); i++) {
            var segment = path.segments().get(i);
            var existing = current.child(segment);
            if (existing == null) {
                throw pathNotFound(path.text());
            }
            if (i == path.segments().size() - 1) {
                return existing;
            }
            current = switch (existing) {
                case DirectoryNode directoryNode -> directoryNode;
                case FileNode _ -> throw typeMismatch(
                        path.prefixText(i + 1),
                        VfsEntrySnapshot.Kind.FILE,
                        VfsEntrySnapshot.Kind.DIRECTORY
                );
            };
        }
        throw new IllegalStateException("Failed to resolve path: " + path.text());
    }

    private @NotNull DirectoryNode requireDirectory(@NotNull VirtualPath path) {
        return switch (requireNode(path)) {
            case DirectoryNode directoryNode -> directoryNode;
            case FileNode _ -> throw typeMismatch(
                    path.text(),
                    VfsEntrySnapshot.Kind.FILE,
                    VfsEntrySnapshot.Kind.DIRECTORY
            );
        };
    }

    private @NotNull ApiPathNotFoundException pathNotFound(@NotNull String pathText) {
        return new ApiPathNotFoundException("Path '" + pathText + "' does not exist in module '" + moduleId + "'");
    }

    private @NotNull ApiEntryTypeMismatchException typeMismatch(
            @NotNull String pathText,
            @NotNull VfsEntrySnapshot.Kind actualKind,
            @NotNull VfsEntrySnapshot.Kind expectedKind
    ) {
        return new ApiEntryTypeMismatchException(
                "Path '" + pathText + "' in module '" + moduleId + "' is a "
                        + kindLabel(actualKind)
                        + ", not a "
                        + kindLabel(expectedKind)
        );
    }

    private @NotNull String kindLabel(@NotNull VfsEntrySnapshot.Kind kind) {
        return switch (kind) {
            case DIRECTORY -> "directory";
            case FILE -> "file";
        };
    }
}
