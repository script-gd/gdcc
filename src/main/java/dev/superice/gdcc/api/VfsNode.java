package dev.superice.gdcc.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

sealed interface VfsNode permits DirectoryNode, FileNode, LinkNode {
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

    @NotNull Iterable<Map.Entry<String, VfsNode>> children() {
        return children.entrySet();
    }

    public @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot snapshot(@NotNull VirtualPath path) {
        return new VfsEntrySnapshot.DirectoryEntrySnapshot(path.text(), path.name(), childCount());
    }
}

final class FileNode implements VfsNode {
    private final @NotNull String content;
    private final @NotNull String displayPath;
    private final long byteCount;
    private final @NotNull Instant updatedAt;

    FileNode(@NotNull String content, @NotNull String displayPath, long byteCount, @NotNull Instant updatedAt) {
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.displayPath = Objects.requireNonNull(displayPath, "displayPath must not be null");
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        this.byteCount = byteCount;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static @NotNull FileNode fromContent(
            @NotNull String content,
            @NotNull String displayPath,
            @NotNull Clock clock
    ) {
        var text = Objects.requireNonNull(content, "content must not be null");
        return new FileNode(
                text,
                displayPath,
                text.getBytes(StandardCharsets.UTF_8).length,
                Instant.now(Objects.requireNonNull(clock, "clock must not be null"))
        );
    }

    @NotNull String content() {
        return content;
    }

    @NotNull String displayPath() {
        return displayPath;
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot snapshot(@NotNull VirtualPath path) {
        return new VfsEntrySnapshot.FileEntrySnapshot(path.text(), displayPath, path.name(), byteCount, updatedAt);
    }
}

final class LinkNode implements VfsNode {
    private final @NotNull VfsEntrySnapshot.LinkKind linkKind;
    private final @NotNull String target;
    private final @Nullable VirtualPath virtualTarget;

    private LinkNode(
            @NotNull VfsEntrySnapshot.LinkKind linkKind,
            @NotNull String target,
            @Nullable VirtualPath virtualTarget
    ) {
        this.linkKind = Objects.requireNonNull(linkKind, "linkKind must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.virtualTarget = virtualTarget;
    }

    static @NotNull LinkNode virtual(@NotNull VirtualPath target) {
        var normalizedTarget = Objects.requireNonNull(target, "target must not be null");
        return new LinkNode(VfsEntrySnapshot.LinkKind.VIRTUAL, normalizedTarget.text(), normalizedTarget);
    }

    static @NotNull LinkNode local(@NotNull String target) {
        var normalizedTarget = Objects.requireNonNull(target, "target must not be null");
        return new LinkNode(VfsEntrySnapshot.LinkKind.LOCAL, normalizedTarget, null);
    }

    @NotNull VfsEntrySnapshot.LinkKind linkKind() {
        return linkKind;
    }

    @NotNull String target() {
        return target;
    }

    @NotNull VirtualPath requireVirtualTarget() {
        if (virtualTarget == null) {
            throw new IllegalStateException("Link is not a virtual link: " + target);
        }
        return virtualTarget;
    }

    @NotNull VfsEntrySnapshot.LinkEntrySnapshot snapshot(
            @NotNull VirtualPath path,
            @Nullable VfsEntrySnapshot.BrokenReason brokenReason
    ) {
        return new VfsEntrySnapshot.LinkEntrySnapshot(path.text(), path.name(), linkKind, target, brokenReason);
    }
}
