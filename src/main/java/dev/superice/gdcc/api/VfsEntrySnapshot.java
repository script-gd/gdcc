package dev.superice.gdcc.api;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/// Frozen VFS entry metadata returned by the RPC-friendly API facade.
///
/// Content reads stay on the dedicated `readFile(...)` surface so directory listings do not
/// accidentally copy file bodies across the RPC boundary.
public sealed interface VfsEntrySnapshot permits VfsEntrySnapshot.DirectoryEntrySnapshot,
        VfsEntrySnapshot.FileEntrySnapshot,
        VfsEntrySnapshot.LinkEntrySnapshot {
    @NotNull String path();

    @NotNull String name();

    @NotNull Kind kind();

    enum Kind {
        DIRECTORY,
        FILE,
        LINK
    }

    enum LinkKind {
        VIRTUAL,
        LOCAL
    }

    enum BrokenReason {
        MISSING_TARGET,
        CYCLE
    }

    record DirectoryEntrySnapshot(@NotNull String path, @NotNull String name,
                                  int childCount) implements VfsEntrySnapshot {
        public DirectoryEntrySnapshot {
            path = VirtualPath.parse(path).text();
            name = validateName(name);
            if (childCount < 0) {
                throw new IllegalArgumentException("childCount must not be negative");
            }
        }

        @Override
        public @NotNull Kind kind() {
            return Kind.DIRECTORY;
        }
    }

    record FileEntrySnapshot(
            @NotNull String path,
            @NotNull String name,
            long byteCount,
            @NotNull Instant updatedAt
    ) implements VfsEntrySnapshot {
        public FileEntrySnapshot {
            path = VirtualPath.parse(path).text();
            name = validateName(name);
            if (byteCount < 0) {
                throw new IllegalArgumentException("byteCount must not be negative");
            }
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }

        @Override
        public @NotNull Kind kind() {
            return Kind.FILE;
        }
    }

    record LinkEntrySnapshot(
            @NotNull String path,
            @NotNull String name,
            @NotNull LinkKind linkKind,
            @NotNull String target,
            @Nullable BrokenReason brokenReason
    ) implements VfsEntrySnapshot {
        public LinkEntrySnapshot {
            path = VirtualPath.parse(path).text();
            name = validateName(name);
            Objects.requireNonNull(linkKind, "linkKind must not be null");
            target = validateTarget(linkKind, target);
        }

        @Override
        public @NotNull Kind kind() {
            return Kind.LINK;
        }

        public boolean broken() {
            return brokenReason != null;
        }
    }

    private static @NotNull String validateName(@NotNull String name) {
        var value = Objects.requireNonNull(name, "name must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        return value;
    }

    private static @NotNull String validateTarget(@NotNull LinkKind linkKind, @NotNull String target) {
        return switch (linkKind) {
            case VIRTUAL -> VirtualPath.parse(target).text();
            case LOCAL -> StringUtil.requireTrimmedNonBlank(target, "target");
        };
    }
}
