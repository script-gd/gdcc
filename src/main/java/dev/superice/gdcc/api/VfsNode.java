package dev.superice.gdcc.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

sealed interface VfsNode permits DirectoryNode, FileNode {
    @NotNull VfsEntrySnapshot snapshot(@NotNull VirtualPath path);
}

final class DirectoryNode implements VfsNode {
    private final @NotNull NavigableMap<String, VfsNode> children = new TreeMap<>();

    int childCount() {
        return children.size();
    }

    boolean hasChildren() {
        return !children.isEmpty();
    }

    @Nullable VfsNode child(@NotNull String name) {
        return children.get(name);
    }

    void putChild(@NotNull String name, @NotNull VfsNode node) {
        children.put(name, node);
    }

    void removeChild(@NotNull String name) {
        children.remove(name);
    }

    @NotNull List<VfsEntrySnapshot> listChildren(@NotNull VirtualPath directoryPath) {
        var entries = new ArrayList<VfsEntrySnapshot>(children.size());
        for (var childEntry : children.entrySet()) {
            entries.add(childEntry.getValue().snapshot(directoryPath.child(childEntry.getKey())));
        }
        return entries;
    }

    @Override
    public @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot snapshot(@NotNull VirtualPath path) {
        return new VfsEntrySnapshot.DirectoryEntrySnapshot(path.text(), path.name(), childCount());
    }
}

final class FileNode implements VfsNode {
    private final @NotNull String content;
    private final long byteCount;
    private final @NotNull Instant updatedAt;

    FileNode(@NotNull String content, long byteCount, @NotNull Instant updatedAt) {
        this.content = Objects.requireNonNull(content, "content must not be null");
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        this.byteCount = byteCount;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static @NotNull FileNode fromContent(@NotNull String content, @NotNull Clock clock) {
        var text = Objects.requireNonNull(content, "content must not be null");
        return new FileNode(
                text,
                text.getBytes(StandardCharsets.UTF_8).length,
                Instant.now(Objects.requireNonNull(clock, "clock must not be null"))
        );
    }

    @NotNull String content() {
        return content;
    }

    @Override
    public @NotNull VfsEntrySnapshot.FileEntrySnapshot snapshot(@NotNull VirtualPath path) {
        return new VfsEntrySnapshot.FileEntrySnapshot(path.text(), path.name(), byteCount, updatedAt);
    }
}
