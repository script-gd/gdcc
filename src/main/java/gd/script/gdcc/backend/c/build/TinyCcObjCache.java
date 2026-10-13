package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/// Content-addressed per-TU object cache for the tcc backend: `<cacheRoot>/tcc-obj/<key>.o`.
/// A hit skips the TU's compilation entirely; a miss compiles to a unique same-directory temp
/// name and atomically publishes it.
///
/// Key obligation (single point, keep complete — a missing input is a wrong hit): format
/// version, bundle `VERSION` content (compiler identity), the fixed compilation inputs
/// (`-std=c11` + object output semantics), `TargetPlatform`, the include directories in `-I`
/// order (normalized absolute path + whole-tree content hash), the TU's normalized absolute
/// path (`STT_FILE`/`__FILE__` bake it into the object bytes), the TU content, and the TU
/// directory's top-level `*.h` files (name + content, sorted) — quote-includes resolve next to
/// the including file, so generated headers like `entry.h` are real compilation inputs even
/// though they never appear in `includeDirs`. The optimization level is deliberately absent:
/// tcc has no effective optimization pipeline, so DEBUG and RELEASE rounds share entries.
/// `bundleRoot` and host system headers never enter the key.
///
/// Publication is atomic-only: `ATOMIC_MOVE` + `REPLACE_EXISTING` (last-writer-wins for
/// cross-process same-key writers); an [java.nio.file.AtomicMoveNotSupportedException]
/// propagates so the caller degrades — a non-atomic overwrite of the final path is forbidden
/// because a half-written entry would be consumed by the regular-file hit check.
final class TinyCcObjCache {

    /// Bumped when the key document shape changes, invalidating every earlier entry. v2 added
    /// the TU-adjacent header section.
    static final String KEY_FORMAT_VERSION = "gdcc-tcc-obj-v2";
    static final String OBJ_DIR_NAME = "tcc-obj";
    /// SHA-256 truncated to 16 bytes (32 hex chars) — same shape as the zig PCH key.
    private static final int KEY_HASH_BYTES = 16;
    /// `.o` on every platform: libtcc emits ELF objects even for PE targets.
    private static final String OBJ_EXTENSION = ".o";

    private TinyCcObjCache() {
    }

    /// Hashes every include directory tree once per round; the results feed [requireKey] via
    /// `includeTreeHashes` so an N-TU round walks each tree exactly once instead of per TU.
    /// Directory symlinks would be followed by the native include search but not by the tree
    /// walk, so their targets' contents could change without changing the hash — reject them
    /// (the caller degrades the round to cacheless) instead of risking a wrong hit. File
    /// symlinks are fine: their target content is hashed.
    static @NotNull List<String> hashIncludeTrees(@NotNull List<Path> includeDirs) throws IOException {
        var hashes = new ArrayList<String>(includeDirs.size());
        for (var includeDir : includeDirs) {
            hashes.add(CContentHash.hashIncludeTree(includeDir.toAbsolutePath().normalize(), true));
        }
        return hashes;
    }

    /// Computes the content key for one TU. `tuContent` is read by the caller (a TU read
    /// failure degrades to a plain miss, not a cache outage); `includeTreeHashes` comes from
    /// [hashIncludeTrees] (its failure degrades the whole round to cacheless); a TU-adjacent
    /// header read failure surfaces as [IOException] with the same round-level degradation.
    static @NotNull String requireKey(@NotNull Path tuFile, byte @NotNull [] tuContent, @NotNull List<Path> includeDirs,
                                      @NotNull List<String> includeTreeHashes, @NotNull String bundleVersion,
                                      @NotNull TargetPlatform targetPlatform) throws IOException {
        if (includeDirs.size() != includeTreeHashes.size()) {
            throw new IllegalArgumentException("includeTreeHashes must parallel includeDirs");
        }
        var normalizedTu = tuFile.toAbsolutePath().normalize();
        var document = new StringBuilder();
        document.append("format=").append(KEY_FORMAT_VERSION).append('\n');
        document.append("fixed-inputs=-std=c11|obj\n");
        document.append("target=").append(targetPlatform.name()).append('\n');
        for (var i = 0; i < includeDirs.size(); i++) {
            var dir = includeDirs.get(i).toAbsolutePath().normalize();
            document.append("include[").append(i).append("]=").append(dir).append('\n');
            document.append("include-tree-sha256[").append(i).append("]=").append(includeTreeHashes.get(i)).append('\n');
        }
        document.append("tu-path=").append(normalizedTu).append('\n');
        var digest = CContentHash.newSha256();
        // Every section is length-prefixed: the bundle VERSION and the TU content are
        // multi-line blobs whose boundaries must never blur into the single-line fields.
        CContentHash.updateLengthPrefixed(digest, document.toString().getBytes(StandardCharsets.UTF_8));
        CContentHash.updateLengthPrefixed(digest, bundleVersion.getBytes(StandardCharsets.UTF_8));
        CContentHash.updateLengthPrefixed(digest, tuContent);
        hashTuAdjacentHeaders(digest, normalizedTu);
        return HexFormat.of().formatHex(digest.digest(), 0, KEY_HASH_BYTES);
    }

    /// Quote-includes resolve next to the including file before the `-I` paths, so the TU
    /// directory's top-level headers are compilation inputs that `includeDirs` never cover.
    /// Their sorted names and contents join the key; a read failure degrades the round (the
    /// headers may be generated and simply absent when the TU never quote-includes).
    private static void hashTuAdjacentHeaders(@NotNull java.security.MessageDigest digest, @NotNull Path normalizedTu) throws IOException {
        var tuDir = normalizedTu.getParent();
        final List<Path> headers;
        if (tuDir == null || !Files.isDirectory(tuDir)) {
            headers = List.of();
        } else {
            try (var stream = Files.list(tuDir)) {
                headers = stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".h"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
        }
        CContentHash.updateLengthPrefixed(digest, ("tu-headers-count=" + headers.size() + "\n").getBytes(StandardCharsets.UTF_8));
        for (var header : headers) {
            CContentHash.updateLengthPrefixed(digest, header.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            CContentHash.updateLengthPrefixed(digest, Files.readAllBytes(header));
        }
    }

    /// Hit check: the entry is consumed only when it exists as a regular file. Any lookup
    /// problem (missing root, unreadable directory) reports a plain miss.
    static @Nullable Path findHit(@NotNull Path cacheRoot, @NotNull String key) {
        var entry = finalPath(cacheRoot, key);
        return Files.isRegularFile(entry) ? entry : null;
    }

    /// Unique same-directory temp name (`<key>.tmp-<random>.o`) for this round's miss output:
    /// the `.o` extension steers libtcc's link-time `guess_filetype` toward the object class,
    /// and the random infix keeps the temp path distinct from the final path and from other
    /// rounds. Creates the cache directory on first use.
    static @NotNull Path newTempObjectPath(@NotNull Path cacheRoot, @NotNull String key) throws IOException {
        var directory = Files.createDirectories(objectDirectory(cacheRoot));
        return Files.createTempFile(directory, key + ".tmp-", OBJ_EXTENSION);
    }

    /// Atomically publishes a verified temp object as `<key>.o`, replacing any same-key entry
    /// (concurrent writers race last-writer-wins with interchangeable content).
    static @NotNull Path publish(@NotNull Path cacheRoot, @NotNull String key, @NotNull Path tempObject) throws IOException {
        var entry = finalPath(cacheRoot, key);
        Files.move(tempObject, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return entry;
    }

    /// Drops a cached entry after the linker rejected it (poisoning/external tampering), so the
    /// self-heal recompile starts from a clean slot.
    static void evict(@NotNull Path cacheRoot, @NotNull String key) throws IOException {
        Files.deleteIfExists(finalPath(cacheRoot, key));
    }

    static @NotNull Path objectDirectory(@NotNull Path cacheRoot) {
        return cacheRoot.resolve(OBJ_DIR_NAME);
    }

    static @NotNull Path finalPath(@NotNull Path cacheRoot, @NotNull String key) {
        return objectDirectory(cacheRoot).resolve(key + OBJ_EXTENSION);
    }
}
