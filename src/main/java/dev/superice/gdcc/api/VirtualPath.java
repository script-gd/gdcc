package dev.superice.gdcc.api;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Normalized absolute VFS path owned by the API layer.
///
/// RPC callers always speak POSIX-style virtual paths so module state never leaks host-specific
/// path semantics into parser or backend-facing code.
public record VirtualPath(@NotNull String text, @NotNull List<String> segments) {
    public VirtualPath {
        text = Objects.requireNonNull(text, "text must not be null");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
    }

    public static @NotNull VirtualPath parse(@NotNull String rawPath) {
        var text = Objects.requireNonNull(rawPath, "path must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (text.contains("\\")) {
            throw invalid(text, "path must use '/' separators");
        }
        if (!text.startsWith("/")) {
            throw invalid(text, "path must start with '/'");
        }
        if (text.equals("/")) {
            return new VirtualPath("/", List.of());
        }

        var rawSegments = text.substring(1).split("/", -1);
        var normalizedSegments = new ArrayList<String>(rawSegments.length);
        for (var rawSegment : rawSegments) {
            validateSegment(rawSegment, text);
            normalizedSegments.add(rawSegment);
        }
        return new VirtualPath("/" + String.join("/", normalizedSegments), normalizedSegments);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    @NotNull public String name() {
        return isRoot() ? "/" : segments.getLast();
    }

    @NotNull public String prefixText(int segmentCount) {
        if (segmentCount < 0 || segmentCount > segments.size()) {
            throw new IllegalArgumentException("segmentCount out of range: " + segmentCount);
        }
        if (segmentCount == 0) {
            return "/";
        }
        return "/" + String.join("/", segments.subList(0, segmentCount));
    }

    @NotNull public VirtualPath child(@NotNull String segment) {
        validateSegment(Objects.requireNonNull(segment, "segment must not be null"), segment);
        var childSegments = new ArrayList<String>(segments.size() + 1);
        childSegments.addAll(segments);
        childSegments.add(segment);
        return new VirtualPath(isRoot() ? "/" + segment : text + "/" + segment, childSegments);
    }

    @Override
    public @NotNull String toString() {
        return text;
    }

    private static void validateSegment(@NotNull String segment, @NotNull String rawPath) {
        if (segment.isEmpty()) {
            throw invalid(rawPath, "path must not contain empty segments");
        }
        if (segment.contains("/") || segment.contains("\\")) {
            throw invalid(rawPath, "path segment must not contain separators");
        }
        if (segment.equals(".")) {
            throw invalid(rawPath, "path must not contain '.' segments");
        }
        if (segment.equals("..")) {
            throw invalid(rawPath, "path must not contain '..' segments");
        }
    }

    private static @NotNull IllegalArgumentException invalid(@NotNull String rawPath, @NotNull String message) {
        return new IllegalArgumentException(message + ": '" + rawPath + "'");
    }
}
