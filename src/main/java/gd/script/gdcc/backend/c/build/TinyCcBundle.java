package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.GdccVersion;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/// Locates the platform tinycc bundle for the in-process libtcc backend: a directory carrying
/// the libtcc shared library plus everything `tcc_set_lib_path` needs to compile and link.
///
/// Resolution order:
/// 1. `GDCC_TINYCC_HOME` — used in place, never copied; it must satisfy the full layout,
///    `VERSION` and MANIFEST integrity contract, so a stale, corrupted or wrong-platform
///    directory fails fast with a message naming the variable.
/// 2. Otherwise the bundle is installed from classpath resources (`gdcc/tinycc-bundle/<key>/`)
///    into a versioned user cache directory and reused from there.
///
/// Bundle layout contract (validated on every resolution):
/// - both platforms: `VERSION`, `MANIFEST`, `COPYING`, `RELICENSING`, and tinycc's own headers
///   under `include/` (`tccdefs.h`, `stddef.h`, `stdarg.h` — these are NOT provided by the host
///   C library headers, so a bundle without them cannot compile anything);
/// - `linux-x86_64`: `bin/libtcc.so`, and `libtcc1.a` at the bundle ROOT — on Linux the
///   library search path expands `{B}` itself, not `{B}/lib`;
/// - `windows-x86_64`: `bin/libtcc.dll`, `lib/libtcc1.a` (the PE library search path is
///   `{B}/lib`), the merged `include/` tree (`_mingw.h` at the top level, `windows.h` under
///   `include/winapi/` — the PE backend searches both) and the import definitions
///   `lib/{msvcrt,kernel32,user32,gdi32}.def`.
///
/// `VERSION` is a properties document with `bundleFormat` (must equal the
/// `BUNDLE_FORMAT_VERSION` constant), `tinyccCommit` (must equal the `TINYCC_PINNED_COMMIT`
/// constant) and `platform` (must equal the requested platform key). `MANIFEST` holds one
/// sha256sum-style line (`<sha256-hex>  <relative/path>`) per bundle file; every resolution —
/// cache reuse, winner adoption after a rename race, and env overrides alike — re-verifies
/// each listed file's SHA-256, so a corrupted or partially deleted bundle heals instead of
/// reaching the compiler.
///
/// Install protocol into `<cacheRoot>/<bundleKey>/` (safe across processes and threads):
/// - mutual exclusion is two-layered. An in-JVM `ReentrantLock` keyed by the normalized
///   lock-file path is taken FIRST and released LAST: `FileLock` is backed by classic POSIX
///   fcntl locks on Linux/macOS, and those are released when the process closes ANY descriptor
///   of the file — so two channels for one lock file must never coexist in this JVM, and the
///   ReentrantLock serializes open → lock → work → close per key. The `FileLock` on the
///   sibling file `<bundleKey>.lock` (it must live NEXT TO the guarded directory, which gets
///   renamed and deleted) then serializes installers across processes. The in-JVM wait uses
///   `lockInterruptibly`; the cross-process wait is a `tryLock` + interruptible backoff loop —
///   a blocking `channel.lock()` cannot be cancelled promptly on virtual threads, where
///   `interrupt()` would itself block until the holder releases. Both waits restore the
///   interrupt status on cancellation.
/// - the content is staged into a random `.staging-<key>-*` sibling directory, every file is
///   re-hashed against the source-supplied manifest, the layout/`VERSION` contract and the
///   staged MANIFEST's own integrity are checked, and only then is the `.ready` marker
///   written — its presence implies a fully verified tree;
/// - publication is a single atomic rename; if a competing instance won the rename race, its
///   directory is adopted when it verifies, replaced when it does not (the key lock is held in
///   either case);
/// - an existing final path that fails verification (including a regular file or symlink left
///   by a crash) is removed wholesale and reinstalled (self-heal), and staging directories
///   left behind by crashed installers are reclaimed once they are older than the retention
///   window — the key lock guarantees no live installer owns them.
///
/// The class is stateless: once a caller has loaded `bin/libtcc` from a resolved root, that
/// root must be treated as immutable for the rest of the process (consumers cache the path
/// instead of re-resolving mid-flight).
final class TinyCcBundle {
    static final String ENV_HOME = "GDCC_TINYCC_HOME";
    static final String VERSION_FILE_NAME = "VERSION";
    static final String MANIFEST_FILE_NAME = "MANIFEST";
    static final String READY_MARKER_NAME = ".ready";
    static final String LOCK_FILE_SUFFIX = ".lock";
    static final String STAGING_DIR_PREFIX = ".staging-";
    static final String BUNDLE_FORMAT_VERSION = "1";
    static final String TINYCC_PINNED_COMMIT = "43c7708b85681a2fd4451c8a541af4494a8919b2";
    static final String LINUX_X86_64_KEY = "linux-x86_64";
    static final String WINDOWS_X86_64_KEY = "windows-x86_64";
    static final String CLASSPATH_RESOURCE_ROOT = "gdcc/tinycc-bundle";

    /// Crash residue younger than this is left untouched: an installer that is merely slow
    /// (not dead) must never see its staging directory reclaimed from under it.
    private static final Duration STAGING_RETENTION = Duration.ofHours(24);
    private static final long LOCK_RETRY_MILLIS = 25;

    /// In-JVM per-lock-file locks; see the class doc for why the file lock alone is unsafe.
    private static final ConcurrentHashMap<Path, ReentrantLock> INSTALL_LOCKS = new ConcurrentHashMap<>();

    private TinyCcBundle() {
    }

    /// Supplies the bundle content for a cache install. Implementations must write the
    /// complete bundle into the given staging directory and return the integrity manifest as
    /// `relative/path(with '/' separators) -> lowercase SHA-256 hex of the content written`.
    @FunctionalInterface
    interface BundleSource {
        @NotNull Map<String, String> writeTo(@NotNull Path stagingDir) throws IOException;
    }

    /// Production entry: resolves the bundle for the native platform.
    static @NotNull Path requireBundleRoot() throws IOException {
        var platformKey = bundlePlatformKey(TargetPlatform.getNativePlatform());
        return requireBundleRoot(
                System.getenv(ENV_HOME),
                defaultCacheRoot(),
                classpathBundleSource(platformKey, TinyCcBundle.class.getClassLoader()),
                platformKey,
                GdccVersion.current().version()
        );
    }

    /// Full-seam resolution: an explicit environment override, cache root, content source,
    /// platform key and gdcc version make the protocol driveable from tests without touching
    /// the real environment, user cache or classpath.
    static @NotNull Path requireBundleRoot(
            @Nullable String envOverride,
            @NotNull Path cacheRoot,
            @NotNull BundleSource bundleSource,
            @NotNull String platformKey,
            @NotNull String gdccVersion
    ) throws IOException {
        var override = StringUtil.trimToNull(envOverride);
        if (override != null) {
            var root = Path.of(override).toAbsolutePath().normalize();
            var problems = new ArrayList<>(validateBundleProblems(root, platformKey));
            if (problems.isEmpty()) {
                problems.addAll(manifestIntegrityProblems(root));
            }
            if (!problems.isEmpty()) {
                throw new IOException(ENV_HOME + " does not point at a usable tinycc bundle at " + root + ":\n - " + String.join("\n - ", problems));
            }
            return root;
        }

        var key = bundleKey(gdccVersion, platformKey);
        Files.createDirectories(cacheRoot);
        var finalDir = cacheRoot.resolve(key);
        var lockFile = cacheRoot.resolve(key + LOCK_FILE_SUFFIX).toAbsolutePath().normalize();
        var jvmLock = requireInstallLock(lockFile);
        lockInterruptibly(jvmLock);
        try {
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = lockFileLockInterruptibly(channel)) {
                deleteStaleStagingDirs(cacheRoot, key);
                if (checkBundleUsable(finalDir, platformKey)) {
                    return finalDir;
                }
                // Self-heal: whatever sits at the final path and fails verification is
                // removed wholesale rather than patched in place.
                if (!deleteRecursively(finalDir)) {
                    throw new IOException("Could not remove the broken tinycc bundle directory " + finalDir);
                }
                installLocked(cacheRoot, finalDir, key, bundleSource, platformKey);
                return finalDir;
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /// Cross-process wait on the install lock: non-blocking `tryLock` plus an interruptible
    /// backoff. A BLOCKING `channel.lock()` is deliberately not used: on virtual threads the
    /// JDK cannot cancel the native lock wait promptly (`interrupt()` would itself block until
    /// the holder releases), so cooperative cancellation requires this poll. The wait happens
    /// on the single channel opened under the in-JVM lock — never reopen a channel per retry,
    /// because closing one descriptor of a file releases ALL POSIX locks this process holds on
    /// it.
    private static @NotNull FileLock lockFileLockInterruptibly(@NotNull FileChannel channel) throws IOException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted while waiting for the tinycc bundle install lock");
            }
            try {
                var lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Defensive: the in-JVM lock serializes this key's channel, so this should be
                // unreachable; retrying is still the safe response.
            }
            try {
                Thread.sleep(LOCK_RETRY_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the tinycc bundle install lock", exception);
            }
        }
    }

    /// The in-JVM install lock for one lock-file path. Package-private (mirroring the project
    /// build lock holder) so tests can observe queued waiters instead of guessing with sleeps.
    static @NotNull ReentrantLock requireInstallLock(@NotNull Path lockFile) {
        return INSTALL_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize(), key -> new ReentrantLock());
    }

    /// The bundle resource/directory key segment for a platform. Only platforms with a
    /// verified libtcc backend get a key; everything else fails here, before any install or
    /// native work is attempted.
    static @NotNull String bundlePlatformKey(@NotNull TargetPlatform platform) {
        return switch (platform) {
            case LINUX_X86_64 -> LINUX_X86_64_KEY;
            case WINDOWS_X86_64 -> WINDOWS_X86_64_KEY;
            default -> throw new IllegalStateException(
                    "No tinycc bundle is available for " + platform + "; use the zig backend for this target");
        };
    }

    /// Cache directory key: gdcc version + pinned tinycc commit + platform, sanitized so the
    /// key is always a single safe path segment.
    static @NotNull String bundleKey(@NotNull String gdccVersion, @NotNull String platformKey) {
        var sanitizedVersion = gdccVersion.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitizedVersion + "-tinycc-" + TINYCC_PINNED_COMMIT + "-" + platformKey;
    }

    /// The bundle source backed by classpath resources: `<root>/<platformKey>/MANIFEST` lists
    /// every file as `<sha256-hex>  <relative/path>` (sha256sum format), and each entry is
    /// streamed out of the same resource tree. Resources ship inside the JAR, so they are
    /// materialized onto disk here — the native loader cannot open JAR URLs.
    static @NotNull BundleSource classpathBundleSource(@NotNull String platformKey, @NotNull ClassLoader classLoader) {
        var resourceRoot = CLASSPATH_RESOURCE_ROOT + "/" + platformKey;
        return stagingDir -> {
            String manifestText;
            try (var stream = classLoader.getResourceAsStream(resourceRoot + "/" + MANIFEST_FILE_NAME)) {
                if (stream == null) {
                    throw new IOException("No tinycc bundle for platform '" + platformKey + "' on the classpath"
                            + " (missing resource " + resourceRoot + "/" + MANIFEST_FILE_NAME + "); build one with"
                            + " build-script/build-tinycc-bundle-" + platformKey + ".sh and package it, or point "
                            + ENV_HOME + " at a bundle directory");
                }
                manifestText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            // Parse (and thereby validate) the whole manifest before anything is written.
            var manifest = parseManifest(manifestText);
            var stagingRoot = stagingDir.toAbsolutePath().normalize();
            // Stage the manifest itself as well, keeping the installed tree self-describing.
            Files.writeString(stagingDir.resolve(MANIFEST_FILE_NAME), manifestText, StandardCharsets.UTF_8);
            for (var relativePath : manifest.keySet()) {
                var target = stagingRoot.resolve(relativePath).normalize();
                // parseManifest already rejects traversal; this containment check is the second
                // layer standing directly at the write, before any directory is created.
                if (!target.startsWith(stagingRoot)) {
                    throw new IOException("Illegal bundle manifest path escapes the staging directory: " + relativePath);
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                try (var stream = classLoader.getResourceAsStream(resourceRoot + "/" + relativePath)) {
                    if (stream == null) {
                        throw new IOException("Bundle resource listed in MANIFEST is missing: " + resourceRoot + "/" + relativePath);
                    }
                    Files.copy(stream, target);
                }
            }
            return manifest;
        };
    }

    /// Writes, verifies and publishes one bundle. Any failure removes the staging directory;
    /// the final directory is only ever produced by the atomic rename of a verified tree.
    private static void installLocked(
            @NotNull Path cacheRoot,
            @NotNull Path finalDir,
            @NotNull String key,
            @NotNull BundleSource bundleSource,
            @NotNull String platformKey
    ) throws IOException {
        var stagingDir = cacheRoot.resolve(STAGING_DIR_PREFIX + key + "-" + UUID.randomUUID());
        try {
            Files.createDirectories(stagingDir);
            var manifest = bundleSource.writeTo(stagingDir);
            checkStagedContent(stagingDir, manifest);
            var layoutProblems = validateBundleProblems(stagingDir, platformKey);
            if (!layoutProblems.isEmpty()) {
                throw new IOException("The bundle source produced an invalid layout:\n - " + String.join("\n - ", layoutProblems));
            }
            var integrityProblems = manifestIntegrityProblems(stagingDir);
            if (!integrityProblems.isEmpty()) {
                throw new IOException("The bundle source produced an inconsistent MANIFEST:\n - " + String.join("\n - ", integrityProblems));
            }
            // Written last: `.ready` implies content, hashes, layout and VERSION all passed.
            Files.writeString(stagingDir.resolve(READY_MARKER_NAME), "");
            publish(stagingDir, finalDir, platformKey);
        } finally {
            // After a successful publish the staging path no longer exists; on failure this
            // removes the half-written tree so the next attempt starts clean.
            deleteRecursively(stagingDir);
        }
    }

    /// Re-hashes every staged file against the manifest the source claims to have written.
    /// Catching a mismatch here keeps a truncated or corrupted write from ever reaching the
    /// published directory.
    private static void checkStagedContent(@NotNull Path stagingDir, @NotNull Map<String, String> manifest) throws IOException {
        for (var entry : manifest.entrySet()) {
            checkManifestPath(entry.getKey());
            var file = stagingDir.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                throw new IOException("Bundle source manifest entry was not staged: " + entry.getKey());
            }
            var actual = sha256Hex(file);
            if (!actual.equals(entry.getValue())) {
                throw new IOException("Staged bundle file " + entry.getKey() + " hashes to " + actual
                        + " instead of the manifest's " + entry.getValue());
            }
        }
    }

    private static void publish(@NotNull Path stagingDir, @NotNull Path finalDir, @NotNull String platformKey) throws IOException {
        try {
            moveAtomic(stagingDir, finalDir);
            return;
        } catch (FileSystemException exception) {
            // A competing installer published first. The exception type for "target directory
            // already exists and is not empty" varies by platform and move flavor
            // (FileAlreadyExistsException, DirectoryNotEmptyException, or a plain
            // FileSystemException straight from rename(2)), so the race is recognized by the
            // target's presence. This cannot happen while the key lock is honored by every
            // process, but it is still handled defensively: adopt the winner when it verifies,
            // otherwise replace it while holding the lock.
            if (!Files.exists(finalDir)) {
                throw exception;
            }
        }
        if (checkBundleUsable(finalDir, platformKey)) {
            return;
        }
        if (!deleteRecursively(finalDir)) {
            throw new IOException("Could not remove the broken racing tinycc bundle directory " + finalDir);
        }
        moveAtomic(stagingDir, finalDir);
    }

    /// Atomic directory rename; plain move as fallback for filesystems without atomic rename.
    /// `REPLACE_EXISTING` is deliberately absent: an existing final directory must surface as a
    /// [FileSystemException] so the winner can be checked instead of silently overwritten.
    private static void moveAtomic(@NotNull Path from, @NotNull Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to);
        }
    }

    /// A cached directory is reusable only when the marker and the full contract hold: the
    /// marker is published last, and re-validating layout/VERSION plus every MANIFEST hash on
    /// each resolution lets a partially damaged or corrupted tree self-heal on the next call.
    private static boolean checkBundleUsable(@NotNull Path dir, @NotNull String platformKey) {
        if (!Files.isRegularFile(dir.resolve(READY_MARKER_NAME))) {
            return false;
        }
        return validateBundleProblems(dir, platformKey).isEmpty() && manifestIntegrityProblems(dir).isEmpty();
    }

    /// Validates the layout entries and the `VERSION` contract, returning every problem found
    /// (an empty list means the bundle at `root` satisfies the layout contract).
    private static @NotNull List<String> validateBundleProblems(@NotNull Path root, @NotNull String platformKey) {
        if (!Files.isDirectory(root)) {
            return List.of("bundle directory does not exist: " + root);
        }
        var problems = new ArrayList<String>();
        for (var entry : requiredLayoutEntries(platformKey)) {
            if (entry.endsWith("/")) {
                if (!Files.isDirectory(root.resolve(entry.substring(0, entry.length() - 1)))) {
                    problems.add("missing directory " + entry);
                }
            } else if (!Files.isRegularFile(root.resolve(entry))) {
                problems.add("missing file " + entry);
            }
        }
        problems.addAll(checkVersionProblems(root, platformKey));
        return problems;
    }

    /// Re-verifies the tree against its own MANIFEST: the manifest must be non-empty, every
    /// listed file must exist with a matching SHA-256, and — fail closed — every regular file
    /// in the tree (except the install metadata MANIFEST/.ready) must be listed. The coverage
    /// direction is what stops an empty or line-boundary-truncated manifest from waving a
    /// corrupted bundle through. This is what makes post-publish damage (truncated library,
    /// deleted header, tampered file) detectable on the reuse path instead of at native load
    /// time.
    private static @NotNull List<String> manifestIntegrityProblems(@NotNull Path root) {
        Map<String, String> manifest;
        try {
            manifest = parseManifest(Files.readString(root.resolve(MANIFEST_FILE_NAME), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return List.of("unreadable or malformed MANIFEST: " + exception.getMessage());
        }
        if (manifest.isEmpty()) {
            return List.of("MANIFEST covers no files");
        }
        var problems = new ArrayList<String>();
        for (var entry : manifest.entrySet()) {
            var file = root.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                problems.add("MANIFEST entry is missing: " + entry.getKey());
                continue;
            }
            final String actual;
            try {
                actual = sha256Hex(file);
            } catch (IOException exception) {
                problems.add("MANIFEST entry is unreadable: " + entry.getKey());
                continue;
            }
            if (!actual.equals(entry.getValue())) {
                problems.add("MANIFEST entry is corrupted: " + entry.getKey());
            }
        }
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (relative.equals(MANIFEST_FILE_NAME) || relative.equals(READY_MARKER_NAME)) {
                    continue;
                }
                if (!manifest.containsKey(relative)) {
                    problems.add("file is not covered by MANIFEST: " + relative);
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            // Files.walk reports mid-traversal failures as UncheckedIOException; either way the
            // tree is unverifiable, which must surface as a problem instead of an escape.
            problems.add("could not walk the bundle tree: " + exception.getMessage());
        }
        return problems;
    }

    /// Required entries per platform; a trailing `/` marks a directory entry. The `libtcc1.a`
    /// placement differs by design: Linux searches `{B}` itself for libraries while the PE
    /// backend searches `{B}/lib`, so each platform's archive must sit where its
    /// `tcc_set_lib_path` expansion looks.
    private static @NotNull List<String> requiredLayoutEntries(@NotNull String platformKey) {
        var common = List.of(
                MANIFEST_FILE_NAME,
                "COPYING",
                "RELICENSING",
                "include/tccdefs.h",
                "include/stddef.h",
                "include/stdarg.h"
        );
        var platformSpecific = switch (platformKey) {
            case LINUX_X86_64_KEY -> List.of(
                    "bin/libtcc.so",
                    "libtcc1.a"
            );
            case WINDOWS_X86_64_KEY -> List.of(
                    "bin/libtcc.dll",
                    "lib/libtcc1.a",
                    "include/_mingw.h",
                    "include/winapi/",
                    "include/winapi/windows.h",
                    "lib/msvcrt.def",
                    "lib/kernel32.def",
                    "lib/user32.def",
                    "lib/gdi32.def"
            );
            default -> throw new IllegalStateException("No tinycc bundle layout is defined for platform key '" + platformKey + "'");
        };
        var entries = new ArrayList<String>(common);
        entries.addAll(platformSpecific);
        return entries;
    }

    private static @NotNull List<String> checkVersionProblems(@NotNull Path root, @NotNull String platformKey) {
        var versionFile = root.resolve(VERSION_FILE_NAME);
        if (!Files.isRegularFile(versionFile)) {
            return List.of("missing file " + VERSION_FILE_NAME);
        }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(versionFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return List.of("unreadable " + VERSION_FILE_NAME + ": " + exception.getMessage());
        }
        var problems = new ArrayList<String>();
        var format = StringUtil.trimToNull(properties.getProperty("bundleFormat"));
        if (format == null) {
            problems.add(VERSION_FILE_NAME + " is missing 'bundleFormat'");
        } else if (!format.equals(BUNDLE_FORMAT_VERSION)) {
            problems.add(VERSION_FILE_NAME + " bundleFormat '" + format + "' is not supported (expected " + BUNDLE_FORMAT_VERSION + ")");
        }
        var commit = StringUtil.trimToNull(properties.getProperty("tinyccCommit"));
        if (commit == null) {
            problems.add(VERSION_FILE_NAME + " is missing 'tinyccCommit'");
        } else if (!commit.equals(TINYCC_PINNED_COMMIT)) {
            problems.add(VERSION_FILE_NAME + " tinyccCommit '" + commit + "' does not match the pinned commit " + TINYCC_PINNED_COMMIT);
        }
        var platform = StringUtil.trimToNull(properties.getProperty("platform"));
        if (platform == null) {
            problems.add(VERSION_FILE_NAME + " is missing 'platform'");
        } else if (!platform.equals(platformKey)) {
            problems.add(VERSION_FILE_NAME + " platform '" + platform + "' does not match the requested '" + platformKey + "'");
        }
        return problems;
    }

    /// In-JVM wait on a per-key install lock with the interrupt status preserved for callers
    /// that report cancellation through result objects rather than exceptions.
    private static void lockInterruptibly(@NotNull ReentrantLock lock) throws IOException {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the tinycc bundle install lock", exception);
        }
    }

    /// Reclaims staging directories of THIS key that outlived their installer. Holding the key
    /// lock proves no live installer owns them; the retention window is a second, conservative
    /// guard for the paused-vs-crashed distinction across lock implementations. Entirely
    /// best-effort: residue is inert, so a cleanup hiccup must never fail the resolution.
    private static void deleteStaleStagingDirs(@NotNull Path cacheRoot, @NotNull String key) {
        var prefix = STAGING_DIR_PREFIX + key + "-";
        var cutoff = Instant.now().minus(STAGING_RETENTION);
        final List<Path> candidates;
        try (var entries = Files.list(cacheRoot)) {
            candidates = entries.filter(Files::isDirectory).toList();
        } catch (IOException | UncheckedIOException exception) {
            return;
        }
        for (var entry : candidates) {
            try {
                if (!entry.getFileName().toString().startsWith(prefix)) {
                    continue;
                }
                if (Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                    deleteRecursively(entry);
                }
            } catch (IOException exception) {
                // The next resolution retries.
            }
        }
    }

    /// Best-effort recursive delete; `false` means something survived and the caller must not
    /// build on top of the dirty path. A regular file or symlink sitting where a directory is
    /// expected is deleted as itself — `Files.walk` does not follow links, so a symlink's
    /// target is never touched. Mid-traversal failures surface as UncheckedIOException and are
    /// just "could not delete".
    private static boolean deleteRecursively(@NotNull Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (var walk = Files.walk(path)) {
            var paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (var entry : paths) {
                Files.deleteIfExists(entry);
            }
            return true;
        } catch (IOException | UncheckedIOException exception) {
            return false;
        }
    }

    /// Parses one MANIFEST line set in sha256sum format (`<64 hex> <space|*> <path>`) into an
    /// ordered map. Malformed content fails the install before a single byte is staged.
    private static @NotNull Map<String, String> parseManifest(@NotNull String manifestText) throws IOException {
        var manifest = new LinkedHashMap<String, String>();
        for (var line : StringUtil.splitLines(manifestText)) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() < 67 || !isLowerHex(line.substring(0, 64)) || line.charAt(64) != ' ') {
                throw new IOException("Malformed bundle MANIFEST line: " + line);
            }
            var path = line.substring(65);
            if (path.startsWith("*") || path.startsWith(" ")) {
                path = path.substring(1);
            }
            checkManifestPath(path);
            manifest.put(path, line.substring(0, 64));
        }
        return manifest;
    }

    /// Manifest paths must stay inside the staging directory: reject anything absolute on any
    /// platform (POSIX root, drive letters like `C:/`, UNC), Windows separators, any traversal
    /// segment, and anything the local filesystem cannot parse at all (e.g. NUL) — an
    /// unchecked InvalidPathException here would bypass self-heal and override diagnostics.
    private static void checkManifestPath(@NotNull String relativePath) throws IOException {
        if (relativePath.isEmpty()
                || relativePath.startsWith("/")
                || relativePath.contains("\\")
                || relativePath.contains(":")) {
            throw new IOException("Illegal bundle manifest path: " + relativePath);
        }
        for (var segment : relativePath.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Illegal bundle manifest path: " + relativePath);
            }
        }
        try {
            Path.of(relativePath);
        } catch (InvalidPathException exception) {
            throw new IOException("Illegal bundle manifest path: " + relativePath, exception);
        }
    }

    private static boolean isLowerHex(@NotNull String text) {
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull String sha256Hex(@NotNull Path file) throws IOException {
        var digest = newSha256();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static @NotNull MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is a required algorithm of every Java runtime", exception);
        }
    }

    /// Per-user cache root: `%LOCALAPPDATA%/gdcc/tinycc` on Windows, `~/.cache/gdcc/tinycc`
    /// elsewhere. Only reachable for platforms that pass the `bundlePlatformKey` gate.
    private static @NotNull Path defaultCacheRoot() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            var localAppData = StringUtil.trimToNull(System.getenv("LOCALAPPDATA"));
            var base = localAppData != null
                    ? Path.of(localAppData)
                    : Path.of(System.getProperty("user.home"), "AppData", "Local");
            return base.resolve("gdcc").resolve("tinycc");
        }
        return Path.of(System.getProperty("user.home"), ".cache", "gdcc", "tinycc");
    }
}
