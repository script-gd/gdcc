package dev.superice.gdcc.api;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/// Frozen VFS entry metadata returned by the RPC-friendly API facade.
///
/// Content reads stay on the dedicated `readFile(...)` surface so directory listings do not
/// accidentally copy file bodies across the RPC boundary.
public sealed interface VfsEntrySnapshot permits VfsEntrySnapshot.DirectoryEntrySnapshot, VfsEntrySnapshot.FileEntrySnapshot {
    @NotNull String path();

    @NotNull String name();

    @NotNull Kind kind();

    enum Kind {
        DIRECTORY,
        FILE
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

    private static @NotNull String validateName(@NotNull String name) {
        var value = Objects.requireNonNull(name, "name must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        return value;
    }
}
