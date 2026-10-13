package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/// SHA-256 helpers shared by the C compiler caches (zig PCH key, tcc per-TU object key).
/// Extracted from [ZigCcCompiler] verbatim so both backends hash include trees and key
/// documents with exactly the same algorithm; the zig behavior is anchored by
/// `ZigCcCompilerPchKeyTest` staying green after the move.
final class CContentHash {

    private CContentHash() {
    }

    /// Content hash of one include directory tree: regular files are visited in sorted
    /// relative-path order and each file contributes its length-prefixed relative path and
    /// content, so editing, adding or removing any header changes the hash.
    static @NotNull String hashIncludeTree(@NotNull Path includeDir) throws IOException {
        return hashIncludeTree(includeDir, false);
    }

    /// `rejectDirectorySymlinks` for consumers whose native compiler follows directory symlinks
    /// the walk does not: any directory symlink in the tree fails with [IOException] instead of
    /// producing a hash that silently ignores reachable content. File symlinks stay hashed via
    /// their target content, which matches the native read.
    static @NotNull String hashIncludeTree(@NotNull Path includeDir, boolean rejectDirectorySymlinks) throws IOException {
        var digest = newSha256();
        final List<Path> files;
        try (var walk = Files.walk(includeDir)) {
            var entries = walk.sorted(Comparator.comparing(path -> includeDir.relativize(path).toString())).toList();
            if (rejectDirectorySymlinks) {
                for (var entry : entries) {
                    if (Files.isSymbolicLink(entry) && Files.isDirectory(entry)) {
                        throw new IOException("directory symlink in the include tree makes its content unreachable for hashing: " + entry);
                    }
                }
            }
            files = entries.stream().filter(Files::isRegularFile).toList();
        }
        for (var file : files) {
            var relative = includeDir.relativize(file).toString().replace(File.separatorChar, '/');
            updateLengthPrefixed(digest, relative.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(digest, Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Length-prefixing removes any concatenation ambiguity between path/content boundaries.
    static void updateLengthPrefixed(@NotNull MessageDigest digest, byte @NotNull [] bytes) {
        var length = (long) bytes.length;
        for (var i = Long.BYTES - 1; i >= 0; i--) {
            digest.update((byte) (length >>> (i * Byte.SIZE)));
        }
        digest.update(bytes);
    }

    static @NotNull MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is a required algorithm of every Java runtime", exception);
        }
    }
}
