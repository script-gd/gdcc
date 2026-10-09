package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Pure-Java gate for the tinycc bundle resolution contract. Fixture directories stand in for
/// real bundles (content bytes are irrelevant to the protocol), the environment override,
/// cache root, content source, platform key and version are all injected through the seam
/// entry point, and the classpath source is exercised through a [URLClassLoader] rooted at a
/// fixture directory so the test classpath stays free of bundle resources. Anchors:
/// - env override: a valid bundle resolves in place and never touches the cache; every layout,
///   `VERSION` or MANIFEST-integrity gap fails with a message naming `GDCC_TINYCC_HOME` and the
///   concrete problems;
/// - install: content is staged, hash-verified, layout-checked and only then published with
///   `.ready` last; a good install is reused without reconsulting the source, and distinct
///   versions install side by side;
/// - concurrency: contention is made DETERMINISTIC by parking the first installer inside its
///   source (both locks held) and observing the second installer queued on the in-JVM lock;
///   exactly one installer runs, a simulated rename race adopts a verified winner and replaces
///   a corrupted one, and an interrupted waiter keeps the holder's OS-level lock alive —
///   proven by [TinyCcBundleLockProbe], a child process that sees real POSIX lock state;
/// - self-heal: a missing `.ready`, corrupted content (marker intact), a deleted
///   manifest-listed file, a tampered MANIFEST, and a regular file or symlink occupying the
///   final path all trigger a clean reinstall;
/// - failure hygiene: a throwing source, an invalid layout, a stale MANIFEST, a hash mismatch
///   and traversal/drive-letter manifest paths all publish nothing and leave no staging
///   behind.
class TinyCcBundleTest {
    private static final String PLATFORM = TinyCcBundle.LINUX_X86_64_KEY;
    private static final String VERSION = "1.2.3-test";

    @Test
    void envOverrideWithValidBundleResolvesInPlaceWithoutTouchingCache(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");

        var resolved = TinyCcBundle.requireBundleRoot(fixture.toString(), cacheRoot, failingSource(), PLATFORM, VERSION);

        assertEquals(fixture.toAbsolutePath().normalize(), resolved, "the override directory is used in place");
        assertFalse(Files.exists(cacheRoot), "an env override never touches the cache");
    }

    @Test
    void envOverrideWithMissingEntriesFailsAndNamesTheVariableAndEveryGap(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeLinuxBundleFixture(fixture);
        Files.delete(fixture.resolve("bin/libtcc.so"));
        Files.delete(fixture.resolve("libtcc1.a"));

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(fixture.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));

        assertTrue(exception.getMessage().contains("GDCC_TINYCC_HOME"), () -> "message names the variable: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("bin/libtcc.so"), () -> "message lists the missing library: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("libtcc1.a"), () -> "message lists the missing runtime archive: " + exception.getMessage());
    }

    @Test
    void envOverrideRejectsTamperedBundleContent(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeLinuxBundleFixture(fixture);
        // Corrupt a manifest-listed file; layout and VERSION still pass, so only the MANIFEST
        // integrity check can catch this.
        Files.writeString(fixture.resolve("bin/libtcc.so"), "tampered content\n");

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(fixture.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));

        assertTrue(exception.getMessage().contains("GDCC_TINYCC_HOME"), () -> exception.getMessage());
        assertTrue(exception.getMessage().contains("bin/libtcc.so"), () -> "the integrity report names the corrupted file: " + exception.getMessage());
    }

    @Test
    void envOverrideWithNonexistentDirectoryFails(@TempDir Path tempDir) {
        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(tempDir.resolve("nope").toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("GDCC_TINYCC_HOME"), () -> exception.getMessage());
    }

    @Test
    void envOverrideRejectsWrongTinyccCommit(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeLinuxBundleFixture(fixture);
        writeVersionFile(fixture, TinyCcBundle.BUNDLE_FORMAT_VERSION, "0".repeat(40), PLATFORM);

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(fixture.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("tinyccCommit"), () -> exception.getMessage());
        assertTrue(exception.getMessage().contains(TinyCcBundle.TINYCC_PINNED_COMMIT), () -> "message names the pinned commit: " + exception.getMessage());
    }

    @Test
    void envOverrideRejectsWrongPlatform(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeLinuxBundleFixture(fixture);
        writeVersionFile(fixture, TinyCcBundle.BUNDLE_FORMAT_VERSION, TinyCcBundle.TINYCC_PINNED_COMMIT, TinyCcBundle.WINDOWS_X86_64_KEY);

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(fixture.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("platform"), () -> exception.getMessage());
    }

    @Test
    void envOverrideRejectsUnsupportedFormatVersion(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeLinuxBundleFixture(fixture);
        writeVersionFile(fixture, "2", TinyCcBundle.TINYCC_PINNED_COMMIT, PLATFORM);

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(fixture.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("bundleFormat"), () -> exception.getMessage());
    }

    @Test
    void envOverrideRejectsMissingOrMalformedVersionFile(@TempDir Path tempDir) throws IOException {
        var missing = tempDir.resolve("missing");
        writeLinuxBundleFixture(missing);
        Files.delete(missing.resolve("VERSION"));
        var missingException = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(missing.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(missingException.getMessage().contains("VERSION"), () -> missingException.getMessage());

        // Garbage content parses as empty properties, so the required keys surface as missing.
        var malformed = tempDir.resolve("malformed");
        writeLinuxBundleFixture(malformed);
        Files.writeString(malformed.resolve("VERSION"), "this is not a properties document\n");
        var malformedException = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(malformed.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(malformedException.getMessage().contains("bundleFormat"), () -> malformedException.getMessage());
    }

    @Test
    void windowsBundleResolvesThroughEnvOverride(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("bundle");
        writeWindowsBundleFixture(fixture);

        var resolved = TinyCcBundle.requireBundleRoot(fixture.toString(), tempDir.resolve("cache"), failingSource(), TinyCcBundle.WINDOWS_X86_64_KEY, VERSION);

        assertEquals(fixture.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void windowsBundleWithoutWinapiOrImportDefinitionsFails(@TempDir Path tempDir) throws IOException {
        var noWinapi = tempDir.resolve("no-winapi");
        writeWindowsBundleFixture(noWinapi);
        deleteRecursively(noWinapi.resolve("include/winapi"));
        var winapiException = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(noWinapi.toString(), tempDir.resolve("cache"), failingSource(), TinyCcBundle.WINDOWS_X86_64_KEY, VERSION));
        assertTrue(winapiException.getMessage().contains("include/winapi"), () -> winapiException.getMessage());

        var noDefs = tempDir.resolve("no-defs");
        writeWindowsBundleFixture(noDefs);
        Files.delete(noDefs.resolve("lib/gdi32.def"));
        var defException = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(noDefs.toString(), tempDir.resolve("cache"), failingSource(), TinyCcBundle.WINDOWS_X86_64_KEY, VERSION));
        assertTrue(defException.getMessage().contains("lib/gdi32.def"), () -> defException.getMessage());
    }

    @Test
    void installFromSourceCreatesTheVersionedCacheDirectory(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        var expected = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM));
        assertEquals(expected, resolved, "the install lands at <cacheRoot>/<bundleKey>");
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")), "the ready marker is published");
        assertTrue(Files.isRegularFile(resolved.resolve("MANIFEST")), "the manifest is installed with the bundle");
        assertEquals(Files.readString(fixture.resolve("VERSION")), Files.readString(resolved.resolve("VERSION")));
        assertEquals(Files.readString(fixture.resolve("bin/libtcc.so")), Files.readString(resolved.resolve("bin/libtcc.so")));
        assertTrue(Files.isRegularFile(cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM) + ".lock")),
                "the per-key lock file lives next to the bundle directory");
        assertNoStagingResidue(cacheRoot);
    }

    @Test
    void installedBundleIsReusedWithoutReconsultingTheSource(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var first = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        // A second resolution with a source that cannot run proves the installed tree is reused.
        var second = TinyCcBundle.requireBundleRoot(null, cacheRoot, failingSource(), PLATFORM, VERSION);

        assertEquals(first, second);
    }

    @Test
    void differentVersionsInstallSideBySide(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");

        var one = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, "1.0.0");
        var two = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, "2.0.0");

        assertNotEquals(one, two, "the gdcc version is part of the bundle key");
        assertTrue(Files.isRegularFile(one.resolve(".ready")));
        assertTrue(Files.isRegularFile(two.resolve(".ready")));
    }

    @Test
    void unsupportedPlatformsFailBeforeAnyBundleWork(@TempDir Path tempDir) throws IOException {
        var exception = assertThrows(IllegalStateException.class, () -> TinyCcBundle.bundlePlatformKey(TargetPlatform.LINUX_AARCH64));
        assertTrue(exception.getMessage().contains("zig"), () -> "the fallback guidance names the zig backend: " + exception.getMessage());

        // The same gate holds through the resolution seam for a platform key with no layout.
        var unsupportedDir = Files.createDirectories(tempDir.resolve("bundle"));
        assertThrows(IllegalStateException.class,
                () -> TinyCcBundle.requireBundleRoot(unsupportedDir.toString(), tempDir.resolve("cache"), failingSource(), "freebsd-riscv64", VERSION));
    }

    @Test
    void concurrentInstallsSerializeAndPublishExactlyOneBundle(@TempDir Path tempDir) throws Exception {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var lockFile = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM) + TinyCcBundle.LOCK_FILE_SUFFIX);
        // Installer A parks inside its source while holding BOTH locks; installer B must then
        // observably queue on the in-JVM lock — contention is deterministic, never scheduled.
        var sourceEntered = new CountDownLatch(1);
        var releaseSource = new CountDownLatch(1);
        var sourceCalls = new AtomicInteger();
        TinyCcBundle.BundleSource gatedSource = stagingDir -> {
            sourceCalls.incrementAndGet();
            var manifest = directorySource(fixture).writeTo(stagingDir);
            sourceEntered.countDown();
            awaitLatch(releaseSource);
            return manifest;
        };
        var results = Collections.synchronizedList(new ArrayList<Path>());
        var failures = Collections.synchronizedList(new ArrayList<Throwable>());
        var installerA = Thread.ofVirtual().start(() -> {
            try {
                results.add(TinyCcBundle.requireBundleRoot(null, cacheRoot, gatedSource, PLATFORM, VERSION));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        Thread installerB = null;
        try {
            assertTrue(sourceEntered.await(30, TimeUnit.SECONDS), "installer A never entered its source");
            var threadB = Thread.ofVirtual().start(() -> {
                try {
                    results.add(TinyCcBundle.requireBundleRoot(null, cacheRoot, failingSource(), PLATFORM, VERSION));
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            });
            installerB = threadB;
            awaitLockContention(lockFile);
            assertTrue(threadB.isAlive(), "B must be blocked while A holds the install lock");
            assertEquals(List.of(), results, "B may not complete while A is mid-install");
        } finally {
            // Converge the worker threads even when an assertion fails mid-test.
            releaseSource.countDown();
            installerA.join(Duration.ofSeconds(30));
            if (installerB != null) {
                installerB.join(Duration.ofSeconds(30));
            }
        }
        assertFalse(installerA.isAlive() || installerB == null || installerB.isAlive(), "a racing installer hung");

        assertEquals(List.of(), failures, () -> "both installers must succeed: " + failures);
        assertEquals(2, results.size());
        var expected = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM));
        for (var result : results) {
            assertEquals(expected, result);
        }
        assertEquals(1, sourceCalls.get(), "exactly one installer runs; the loser reuses the published tree");
        assertTrue(Files.isRegularFile(expected.resolve(".ready")));
        assertNoStagingResidue(cacheRoot);
    }

    @Test
    void renameConflictAdoptsTheVerifiedWinnerDirectory(@TempDir Path tempDir) throws IOException {
        var loserFixture = tempDir.resolve("loser-fixture");
        writeLinuxBundleFixture(loserFixture);
        tagFixture(loserFixture, "loser");
        var cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(cacheRoot);
        var finalDir = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM));
        // Simulates an installer that won the rename race while this one was staging (only
        // possible when the lock is bypassed, e.g. a deleted lock file): the winner is a full,
        // verified bundle, so resolution must adopt it instead of overwriting it. The two sides
        // are distinguished by MANIFEST-covered COPYING content, never by unmanifested marker
        // files (full coverage is part of the integrity contract).
        TinyCcBundle.BundleSource racingSource = stagingDir -> {
            var manifest = directorySource(loserFixture).writeTo(stagingDir);
            writeLinuxBundleFixture(finalDir);
            tagFixture(finalDir, "winner");
            Files.writeString(finalDir.resolve(".ready"), "");
            return manifest;
        };

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, racingSource, PLATFORM, VERSION);

        assertEquals(finalDir, resolved);
        assertEquals("winner\n", Files.readString(resolved.resolve("COPYING")), "the winner directory is adopted");
        assertNoStagingResidue(cacheRoot);
    }

    @Test
    void renameConflictReplacesACorruptedWinner(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        tagFixture(fixture, "ours");
        var cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(cacheRoot);
        var finalDir = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM));
        // The racing winner carries `.ready` but corrupted content: adoption must verify, not
        // trust the marker, so our own staged tree is published instead.
        TinyCcBundle.BundleSource racingSource = stagingDir -> {
            var manifest = directorySource(fixture).writeTo(stagingDir);
            writeLinuxBundleFixture(finalDir);
            tagFixture(finalDir, "winner");
            Files.writeString(finalDir.resolve("bin/libtcc.so"), "corrupted winner\n");
            Files.writeString(finalDir.resolve(".ready"), "");
            return manifest;
        };

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, racingSource, PLATFORM, VERSION);

        assertEquals(finalDir, resolved);
        assertEquals("ours\n", Files.readString(resolved.resolve("COPYING")), "the corrupted winner is replaced by our verified tree");
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")));
        // The published tree is genuinely valid: a later resolution reuses it without a source.
        assertEquals(resolved, TinyCcBundle.requireBundleRoot(null, cacheRoot, failingSource(), PLATFORM, VERSION));
    }

    @Test
    void missingReadyMarkerTriggersAFullReinstall(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        Files.delete(installed.resolve(".ready"));
        var sourceCalls = new AtomicInteger();
        TinyCcBundle.BundleSource countingSource = stagingDir -> {
            sourceCalls.incrementAndGet();
            return directorySource(fixture).writeTo(stagingDir);
        };

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, countingSource, PLATFORM, VERSION);

        assertEquals(installed, resolved, "the healed bundle keeps its directory");
        assertEquals(1, sourceCalls.get(), "a directory without a ready marker is reinstalled from scratch");
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")), "self-heal republishes the ready marker");
    }

    @Test
    void tamperedContentWithReadyMarkerIntactTriggersAFullReinstall(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        // Marker and layout stay perfect; only the bytes differ. MANIFEST integrity is the
        // only line of defense here.
        Files.writeString(installed.resolve("libtcc1.a"), "corrupted\n");

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        assertEquals(Files.readString(fixture.resolve("libtcc1.a")), Files.readString(resolved.resolve("libtcc1.a")),
                "tampered content is restored by self-heal");
    }

    @Test
    void deletingAManifestListedFileTriggersAFullReinstall(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        // include/stdbool.h is not a layout sentinel; only the MANIFEST notices its loss.
        Files.delete(installed.resolve("include/stdbool.h"));

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        assertTrue(Files.isRegularFile(resolved.resolve("include/stdbool.h")), "the deleted header is restored");
    }

    @Test
    void corruptManifestTriggersAFullReinstall(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        Files.writeString(installed.resolve("MANIFEST"), "garbage\n");

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        assertEquals(Files.readString(fixture.resolve("MANIFEST")), Files.readString(resolved.resolve("MANIFEST")),
                "an unverifiable tree is never reused");
    }

    @Test
    void stagingResidueIsReclaimedOnlyPastRetentionAndOnlyForThisKey(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var key = TinyCcBundle.bundleKey(VERSION, PLATFORM);
        var stale = Files.createDirectories(cacheRoot.resolve(".staging-" + key + "-dead"));
        Files.writeString(stale.resolve("junk"), "junk\n");
        var fresh = Files.createDirectories(cacheRoot.resolve(".staging-" + key + "-alive"));
        var otherKey = Files.createDirectories(cacheRoot.resolve(".staging-unrelated-key-dead"));
        var oldEnough = FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS));
        Files.setLastModifiedTime(stale, oldEnough);
        Files.setLastModifiedTime(otherKey, oldEnough);

        TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        assertFalse(Files.exists(stale), "crash residue past the retention window is reclaimed");
        assertTrue(Files.isDirectory(fresh), "recent staging belongs to a possibly live installer and survives");
        assertTrue(Files.isDirectory(otherKey), "another key's staging is never touched by this key's resolution");
    }

    @Test
    void pausedInstallersStagingIsProtectedUntilTheLockIsReleased(@TempDir Path tempDir) throws Exception {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var key = TinyCcBundle.bundleKey(VERSION, PLATFORM);
        var lockFile = cacheRoot.resolve(key + TinyCcBundle.LOCK_FILE_SUFFIX);
        var sourceEntered = new CountDownLatch(1);
        var releaseSource = new CountDownLatch(1);
        TinyCcBundle.BundleSource gatedSource = stagingDir -> {
            var manifest = directorySource(fixture).writeTo(stagingDir);
            sourceEntered.countDown();
            awaitLatch(releaseSource);
            return manifest;
        };
        var failures = Collections.synchronizedList(new ArrayList<Throwable>());
        var installerA = Thread.ofVirtual().start(() -> {
            try {
                TinyCcBundle.requireBundleRoot(null, cacheRoot, gatedSource, PLATFORM, VERSION);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        Thread installerB = null;
        Path stale;
        try {
            assertTrue(sourceEntered.await(30, TimeUnit.SECONDS));
            // A is parked mid-install, holding both locks, with its fresh staging on disk. Crash
            // residue (past retention) of the same key that appears NOW cannot be cleaned by A —
            // its cleanup pass already ran — and must not be cleaned by B either.
            stale = Files.createDirectories(cacheRoot.resolve(".staging-" + key + "-dead"));
            Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));
            var threadB = Thread.ofVirtual().start(() -> {
                try {
                    TinyCcBundle.requireBundleRoot(null, cacheRoot, failingSource(), PLATFORM, VERSION);
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            });
            installerB = threadB;
            awaitLockContention(lockFile);

            // While A holds the lock, B cannot even reach the cleanup pass: A's staging and the
            // stale residue both survive untouched.
            var stagingWhileLocked = listStagingDirs(cacheRoot);
            assertEquals(2, stagingWhileLocked.size(),
                    () -> "no cleanup may run while an installer holds the key lock: " + stagingWhileLocked);
            assertTrue(threadB.isAlive(), "B must still be waiting for the lock");
        } finally {
            // Converge the worker threads even when an assertion fails mid-test.
            releaseSource.countDown();
            installerA.join(Duration.ofSeconds(30));
            if (installerB != null) {
                installerB.join(Duration.ofSeconds(30));
            }
        }
        assertFalse(installerA.isAlive() || installerB == null || installerB.isAlive(), "an installer hung");
        assertEquals(List.of(), failures, () -> "both installers must succeed: " + failures);
        // After A released the lock, B's pass reclaimed the crash residue and reused the bundle.
        assertFalse(Files.exists(stale), "the stale residue is reclaimed once the lock is free");
        assertNoStagingResidue(cacheRoot);
        assertTrue(Files.isRegularFile(cacheRoot.resolve(key).resolve(".ready")));
    }

    @Test
    void interruptedLockWaiterKeepsTheHoldersOsLevelLockAlive(@TempDir Path tempDir) throws Exception {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var lockFile = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM) + TinyCcBundle.LOCK_FILE_SUFFIX);
        var sourceEntered = new CountDownLatch(1);
        var releaseSource = new CountDownLatch(1);
        TinyCcBundle.BundleSource gatedSource = stagingDir -> {
            var manifest = directorySource(fixture).writeTo(stagingDir);
            sourceEntered.countDown();
            awaitLatch(releaseSource);
            return manifest;
        };
        var failures = Collections.synchronizedList(new ArrayList<Throwable>());
        var installerA = Thread.ofVirtual().start(() -> {
            try {
                TinyCcBundle.requireBundleRoot(null, cacheRoot, gatedSource, PLATFORM, VERSION);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        try {
            assertTrue(sourceEntered.await(30, TimeUnit.SECONDS), "installer A never entered its source");
            // While A holds the lock, an independent process cannot acquire it.
            assertLockProbeReports(lockFile, "HELD");

            var interruptRestored = new AtomicReference<Boolean>();
            var waiterFailure = new AtomicReference<IOException>();
            var waiterB = Thread.ofVirtual().start(() -> {
                try {
                    TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
                } catch (IOException exception) {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                    waiterFailure.set(exception);
                }
            });
            awaitLockContention(lockFile);
            waiterB.interrupt();
            waiterB.join(Duration.ofSeconds(30));
            assertFalse(waiterB.isAlive(), "an interrupted lock wait must not hang");
            assertEquals("Interrupted while waiting for the tinycc bundle install lock", waiterFailure.get().getMessage());
            assertEquals(Boolean.TRUE, interruptRestored.get(), "the interrupt status is restored for the caller");

            // The cancelled waiter must NOT have released A's OS-level lock. Classic POSIX fcntl
            // locks (which back FileLock on Linux) are dropped when the process closes ANY
            // descriptor of the file — this probe is what catches that regression.
            assertLockProbeReports(lockFile, "HELD");
        } finally {
            // Converge the parked installer even when an assertion fails mid-test.
            releaseSource.countDown();
            installerA.join(Duration.ofSeconds(30));
        }
        assertFalse(installerA.isAlive(), "the parked installer hung");
        assertEquals(List.of(), failures, () -> "installer A must succeed: " + failures);
        // Once A fully released the lock (channel closed), the probe can acquire it.
        assertLockProbeReports(lockFile, "ACQUIRED");
    }

    @Test
    void emptyOrTruncatedManifestTriggersAFullReinstall(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        var intactManifest = Files.readString(fixture.resolve("MANIFEST"));

        // An empty manifest parses to zero entries: without fail-closed coverage nothing would
        // be verified at all.
        Files.writeString(installed.resolve("MANIFEST"), "");
        Files.writeString(installed.resolve("libtcc1.a"), "corrupted behind an empty manifest\n");
        assertEquals(intactManifest,
                Files.readString(TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION).resolve("MANIFEST")),
                "an empty MANIFEST must heal, not wave the bundle through");
        assertEquals(Files.readString(fixture.resolve("libtcc1.a")), Files.readString(installed.resolve("libtcc1.a")),
                "the corruption hidden by the empty manifest is healed too");

        // Truncated exactly at a line boundary: every remaining line is valid, but coverage is
        // incomplete — the entries that ARE present still check out.
        var firstLineOnly = intactManifest.lines().findFirst().orElseThrow() + "\n";
        Files.writeString(installed.resolve("MANIFEST"), firstLineOnly);
        assertEquals(intactManifest,
                Files.readString(TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION).resolve("MANIFEST")),
                "a line-boundary-truncated MANIFEST must heal as well");
    }

    @Test
    void manifestWithAnUnparsablePathFailsClosed(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        // A NUL passes every string-level rule but no filesystem can resolve it; it must become
        // a controlled integrity failure, never an unchecked InvalidPathException.
        var poisonedManifest = Files.readString(fixture.resolve("MANIFEST")) + "0".repeat(64) + "  include/\0evil.h\n";

        // Env override: a controlled failure naming the variable and the manifest problem.
        var envFixture = tempDir.resolve("env-fixture");
        writeLinuxBundleFixture(envFixture);
        Files.writeString(envFixture.resolve("MANIFEST"), poisonedManifest);
        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(envFixture.toString(), tempDir.resolve("cache"), failingSource(), PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("GDCC_TINYCC_HOME"), () -> exception.getMessage());
        assertTrue(exception.getMessage().contains("MANIFEST"), () -> exception.getMessage());

        // Cache: an installed bundle whose manifest is poisoned heals on the next resolution.
        var cacheRoot = tempDir.resolve("cache2");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        Files.writeString(installed.resolve("MANIFEST"), poisonedManifest);
        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        assertEquals(Files.readString(fixture.resolve("MANIFEST")), Files.readString(resolved.resolve("MANIFEST")),
                "a bundle with an unverifiable manifest is reinstalled");
    }

    @Test
    void crossProcessLockWaitIsInterruptibleWithTheContractMessage(@TempDir Path tempDir) throws Exception {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var lockFile = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM) + TinyCcBundle.LOCK_FILE_SUFFIX);
        var holder = startLockHolder(lockFile);
        try {
            awaitLockHeldByAnotherProcess(lockFile);
            var interruptRestored = new AtomicReference<Boolean>();
            var waiterFailure = new AtomicReference<IOException>();
            var waiter = Thread.ofVirtual().start(() -> {
                try {
                    TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
                } catch (IOException exception) {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                    waiterFailure.set(exception);
                }
            });
            // Branch readiness without scheduler luck: the in-JVM lock is uncontended here (one
            // CAS), and a waiter that holds it but not the file lock can only be in its
            // interruptible backoff sleep — a blocking channel.lock() implementation would
            // never reach TIMED_WAITING, so the gate fails (rather than faking green) under
            // that regression, and an interrupt delivered before the first lock attempt would
            // never exercise the cancellation path this test anchors.
            awaitCrossProcessLockBackoff(waiter, lockFile);
            // The interrupt must cancel the wait promptly on its own — a blocking
            // channel.lock() would keep even the interrupt() call parked until the holder
            // releases (this JDK's virtual-thread signalAndWait semantics), so both the
            // interrupt RETURN and the waiter exit are bounded, and the holder must still hold
            // the lock at that moment.
            var interruptStart = System.nanoTime();
            waiter.interrupt();
            var interruptReturn = Duration.ofNanos(System.nanoTime() - interruptStart);
            assertTrue(interruptReturn.compareTo(Duration.ofSeconds(10)) < 0,
                    () -> "interrupt() itself blocked for " + interruptReturn);
            waiter.join(Duration.ofSeconds(30));
            assertFalse(waiter.isAlive(), "an interrupted cross-process lock wait must not hang");
            assertTrue(holder.isAlive(), "the holder still owns the lock — the wait was cancelled, not completed");
            assertLockProbeReports(lockFile, "HELD");
            assertEquals("Interrupted while waiting for the tinycc bundle install lock", waiterFailure.get().getMessage());
            assertEquals(Boolean.TRUE, interruptRestored.get(), "the interrupt status is restored for the caller");
        } finally {
            holder.destroyForcibly();
            holder.waitFor(30, TimeUnit.SECONDS);
        }

        // With the holder process gone, installation proceeds normally.
        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")));
    }

    @Test
    void unreadableTreeFailsIntegrityWithControlledDiagnostics(@TempDir Path tempDir) throws Exception {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        var installed = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        // POSIX-only corruption shape: an unreadable EMPTY subdirectory makes tree traversal
        // fail MID-WALK, which the JDK reports as UncheckedIOException — the integrity and
        // cleanup paths must convert that into controlled failures, never let it escape. The
        // subdirectory holds no layout sentinel, so layout and per-file hash checks still pass
        // and the walk is the only thing that can notice.
        var extrasDir = Files.createDirectories(installed.resolve("include/extras"));
        Set<PosixFilePermission> originalPermissions;
        try {
            originalPermissions = Files.getPosixFilePermissions(extrasDir);
            Files.setPosixFilePermissions(extrasDir, Set.of());
        } catch (UnsupportedOperationException exception) {
            Assumptions.abort("POSIX permissions are not supported here");
            return;
        }
        try {
            Assumptions.assumeTrue(!Files.isReadable(extrasDir),
                    "permission checks are bypassed for this user (e.g. root) — cannot simulate an unreadable tree");
            var exception = assertThrows(IOException.class,
                    () -> TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION));
            assertTrue(exception.getMessage().contains("Could not remove the broken tinycc bundle directory"),
                    () -> "unusable-then-unremovable is the controlled failure shape: " + exception.getMessage());
        } finally {
            // Restore permissions so @TempDir cleanup can delete the tree.
            Files.setPosixFilePermissions(extrasDir, originalPermissions);
        }
    }

    @Test
    void failingSourcePublishesNothingAndReleasesTheLock(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        TinyCcBundle.BundleSource failing = stagingDir -> {
            Files.writeString(stagingDir.resolve("partial"), "partial\n");
            throw new IOException("simulated source failure");
        };

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(null, cacheRoot, failing, PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("simulated source failure"), () -> exception.getMessage());
        assertFalse(Files.exists(cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM))), "nothing is published");
        assertNoStagingResidue(cacheRoot);

        // The lock was released: a healthy source installs successfully right after.
        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")));
    }

    @Test
    void sourceProducingAnInvalidBundleIsRejectedBeforePublishing(@TempDir Path tempDir) throws IOException {
        var incomplete = tempDir.resolve("incomplete-fixture");
        writeLinuxBundleFixture(incomplete);
        Files.delete(incomplete.resolve("libtcc1.a"));
        writeManifest(incomplete);
        var cacheRoot = tempDir.resolve("cache");

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(incomplete), PLATFORM, VERSION));

        assertTrue(exception.getMessage().contains("libtcc1.a"), () -> "the layout check names the gap: " + exception.getMessage());
        assertFalse(Files.exists(cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM))), "an invalid bundle never reaches the final directory");
        assertNoStagingResidue(cacheRoot);
    }

    @Test
    void sourceProducingAStaleManifestIsRejectedBeforePublishing(@TempDir Path tempDir) throws IOException {
        var staleManifestFixture = tempDir.resolve("stale-manifest-fixture");
        writeLinuxBundleFixture(staleManifestFixture);
        // Corrupt a file WITHOUT updating the MANIFEST: the source's own reported hashes are
        // honest, so only checking the staged MANIFEST itself catches the inconsistency.
        Files.writeString(staleManifestFixture.resolve("include/stdbool.h"), "tampered after MANIFEST\n");
        var cacheRoot = tempDir.resolve("cache");

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(staleManifestFixture), PLATFORM, VERSION));

        assertTrue(exception.getMessage().contains("stdbool.h"), () -> exception.getMessage());
        assertFalse(Files.exists(cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM))));
        assertNoStagingResidue(cacheRoot);
    }

    @Test
    void manifestHashMismatchFailsTheInstall(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        TinyCcBundle.BundleSource lyingSource = stagingDir -> {
            var manifest = new LinkedHashMap<>(directorySource(fixture).writeTo(stagingDir));
            manifest.put("libtcc1.a", "0".repeat(64));
            return manifest;
        };

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(null, cacheRoot, lyingSource, PLATFORM, VERSION));

        assertTrue(exception.getMessage().contains("libtcc1.a"), () -> exception.getMessage());
        assertFalse(Files.exists(cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM))));
        assertNoStagingResidue(cacheRoot);
    }

    @Test
    void manifestWithTraversalPathIsRejected(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = tempDir.resolve("cache");
        TinyCcBundle.BundleSource traversalSource = stagingDir -> {
            var manifest = new LinkedHashMap<>(directorySource(fixture).writeTo(stagingDir));
            manifest.put("../escape", "0".repeat(64));
            return manifest;
        };

        var exception = assertThrows(IOException.class,
                () -> TinyCcBundle.requireBundleRoot(null, cacheRoot, traversalSource, PLATFORM, VERSION));
        assertTrue(exception.getMessage().contains("Illegal bundle manifest path"), () -> exception.getMessage());
        assertFalse(Files.exists(tempDir.resolve("escape")), "nothing may escape the staging directory");
    }

    @Test
    void classpathSourceInstallsFromPackagedResources(@TempDir Path tempDir) throws IOException {
        var resourceRoot = Files.createDirectories(tempDir.resolve("resources").resolve(TinyCcBundle.CLASSPATH_RESOURCE_ROOT).resolve(PLATFORM));
        writeLinuxBundleFixture(resourceRoot);
        var cacheRoot = tempDir.resolve("cache");
        try (var classLoader = new URLClassLoader(new URL[]{tempDir.resolve("resources").toUri().toURL()}, null)) {
            var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot,
                    TinyCcBundle.classpathBundleSource(PLATFORM, classLoader), PLATFORM, VERSION);

            assertTrue(Files.isRegularFile(resolved.resolve(".ready")));
            assertTrue(Files.isRegularFile(resolved.resolve("MANIFEST")), "the manifest is staged with the bundle");
            assertEquals(Files.readString(resourceRoot.resolve("VERSION")), Files.readString(resolved.resolve("VERSION")));
            assertEquals(Files.readString(resourceRoot.resolve("bin/libtcc.so")), Files.readString(resolved.resolve("bin/libtcc.so")));
        }
    }

    @Test
    void classpathSourceWithoutResourcesFailsWithActionableGuidance(@TempDir Path tempDir) throws IOException {
        var cacheRoot = tempDir.resolve("cache");
        try (var classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null)) {
            var exception = assertThrows(IOException.class,
                    () -> TinyCcBundle.requireBundleRoot(null, cacheRoot,
                            TinyCcBundle.classpathBundleSource(PLATFORM, classLoader), PLATFORM, VERSION));

            assertTrue(exception.getMessage().contains("GDCC_TINYCC_HOME"), () -> "guidance names the override: " + exception.getMessage());
            assertTrue(exception.getMessage().contains("build-script/build-tinycc-bundle-linux-x86_64.sh"),
                    () -> "guidance names the build script: " + exception.getMessage());
        }
        assertFalse(Files.exists(cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM))));
    }

    @Test
    void classpathSourceWithMalformedManifestFailsBeforeStaging(@TempDir Path tempDir) throws IOException {
        var resourceRoot = Files.createDirectories(tempDir.resolve("resources").resolve(TinyCcBundle.CLASSPATH_RESOURCE_ROOT).resolve(PLATFORM));
        writeLinuxBundleFixture(resourceRoot);
        Files.writeString(resourceRoot.resolve("MANIFEST"), "not a sha256 manifest line\n");
        try (var classLoader = new URLClassLoader(new URL[]{tempDir.resolve("resources").toUri().toURL()}, null)) {
            var exception = assertThrows(IOException.class,
                    () -> TinyCcBundle.requireBundleRoot(null, tempDir.resolve("cache"),
                            TinyCcBundle.classpathBundleSource(PLATFORM, classLoader), PLATFORM, VERSION));
            assertTrue(exception.getMessage().contains("Malformed bundle MANIFEST"), () -> exception.getMessage());
        }
    }

    @Test
    void classpathSourceRejectsTraversalAndDriveLetterEntriesBeforeAnyWrite(@TempDir Path tempDir) throws IOException {
        // `C:/evil` is absolute on Windows yet survives naive "starts with /" checks, and
        // `../evil` escapes the staging root; both must be rejected while parsing, before any
        // byte is written.
        for (var badEntry : List.of("../evil.txt", "C:/evil.txt")) {
            var resources = Files.createDirectories(tempDir.resolve("resources-" + badEntry.charAt(0)).resolve(TinyCcBundle.CLASSPATH_RESOURCE_ROOT).resolve(PLATFORM));
            writeLinuxBundleFixture(resources);
            Files.writeString(resources.resolve("MANIFEST"),
                    Files.readString(resources.resolve("MANIFEST")) + "0".repeat(64) + "  " + badEntry + "\n");
            var cacheRoot = tempDir.resolve("cache-" + badEntry.charAt(0));
            try (var classLoader = new URLClassLoader(new URL[]{resources.getParent().getParent().getParent().toUri().toURL()}, null)) {
                var exception = assertThrows(IOException.class,
                        () -> TinyCcBundle.requireBundleRoot(null, cacheRoot,
                                TinyCcBundle.classpathBundleSource(PLATFORM, classLoader), PLATFORM, VERSION));
                assertTrue(exception.getMessage().contains("Illegal bundle manifest path"), () -> exception.getMessage());
            }
            assertFalse(Files.exists(cacheRoot.resolve("evil.txt")), "nothing may escape the staging directory");
            assertNoStagingResidue(cacheRoot);
        }
    }

    @Test
    void regularFileOccupyingTheFinalPathIsHealed(@TempDir Path tempDir) throws IOException {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var finalDir = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM));
        // Crash leftovers are not necessarily directories: a plain file at the final path must
        // be removed by self-heal, not mistaken for "already deleted".
        Files.writeString(finalDir, "junk\n");

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        assertEquals(finalDir, resolved);
        assertTrue(Files.isDirectory(resolved), "the occupying file is replaced by a real bundle directory");
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")));
    }

    @Test
    void symlinkOccupyingTheFinalPathIsReplacedWithoutTouchingItsTarget(@TempDir Path tempDir) throws Exception {
        var fixture = tempDir.resolve("fixture");
        writeLinuxBundleFixture(fixture);
        var cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        var finalDir = cacheRoot.resolve(TinyCcBundle.bundleKey(VERSION, PLATFORM));
        var target = Files.writeString(tempDir.resolve("target.txt"), "do not touch\n");
        try {
            Files.createSymbolicLink(finalDir, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("symlinks are not creatable in this environment");
            return;
        }

        var resolved = TinyCcBundle.requireBundleRoot(null, cacheRoot, directorySource(fixture), PLATFORM, VERSION);

        assertEquals(finalDir, resolved);
        assertFalse(Files.isSymbolicLink(resolved), "the symlink itself is replaced");
        assertTrue(Files.isRegularFile(resolved.resolve(".ready")));
        assertEquals("do not touch\n", Files.readString(target), "the symlink target is never touched");
    }

    /// A valid linux-x86_64 fixture: every layout entry, a pinned-commit VERSION and a MANIFEST
    /// over the whole tree.
    private static void writeLinuxBundleFixture(Path dir) throws IOException {
        writeCommonFixture(dir, PLATFORM);
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("bin/libtcc.so"), "fixture libtcc.so\n");
        Files.writeString(dir.resolve("libtcc1.a"), "fixture libtcc1.a\n");
        writeManifest(dir);
    }

    /// A valid windows-x86_64 fixture: merged include tree (`_mingw.h` at the top level,
    /// `windows.h` under winapi/), import definitions, and the runtime archive under lib/
    /// where the PE library search path looks.
    private static void writeWindowsBundleFixture(Path dir) throws IOException {
        writeCommonFixture(dir, TinyCcBundle.WINDOWS_X86_64_KEY);
        Files.createDirectories(dir.resolve("bin"));
        Files.createDirectories(dir.resolve("lib"));
        Files.createDirectories(dir.resolve("include/winapi"));
        Files.writeString(dir.resolve("bin/libtcc.dll"), "fixture libtcc.dll\n");
        Files.writeString(dir.resolve("lib/libtcc1.a"), "fixture libtcc1.a\n");
        Files.writeString(dir.resolve("include/_mingw.h"), "/* fixture _mingw.h */\n");
        Files.writeString(dir.resolve("include/winapi/windows.h"), "/* fixture windows.h */\n");
        for (var def : List.of("msvcrt", "kernel32", "user32", "gdi32")) {
            Files.writeString(dir.resolve("lib/" + def + ".def"), "LIBRARY " + def + "\n");
        }
        writeManifest(dir);
    }

    private static void writeCommonFixture(Path dir, String platform) throws IOException {
        Files.createDirectories(dir.resolve("include"));
        writeVersionFile(dir, TinyCcBundle.BUNDLE_FORMAT_VERSION, TinyCcBundle.TINYCC_PINNED_COMMIT, platform);
        Files.writeString(dir.resolve("COPYING"), "fixture COPYING\n");
        Files.writeString(dir.resolve("RELICENSING"), "fixture RELICENSING\n");
        Files.writeString(dir.resolve("include/tccdefs.h"), "/* fixture tccdefs */\n");
        Files.writeString(dir.resolve("include/stddef.h"), "/* fixture stddef */\n");
        Files.writeString(dir.resolve("include/stdarg.h"), "/* fixture stdarg */\n");
        Files.writeString(dir.resolve("include/stdbool.h"), "/* fixture stdbool */\n");
    }

    private static void writeVersionFile(Path dir, String format, String commit, String platform) throws IOException {
        Files.writeString(dir.resolve("VERSION"),
                "bundleFormat=" + format + "\ntinyccCommit=" + commit + "\nplatform=" + platform + "\n");
    }

    /// Writes a sha256sum-format MANIFEST over the current fixture content, mirroring what the
    /// bundle build scripts generate. Must be called AFTER all other fixture content exists.
    private static void writeManifest(Path bundleDir) throws IOException {
        var lines = new ArrayList<String>();
        try (var walk = Files.walk(bundleDir)) {
            for (var file : walk.filter(Files::isRegularFile).sorted().toList()) {
                var relative = bundleDir.relativize(file).toString().replace(File.separatorChar, '/');
                if (relative.equals(TinyCcBundle.MANIFEST_FILE_NAME)) {
                    continue;
                }
                lines.add(sha256Hex(file) + "  " + relative);
            }
        }
        Files.writeString(bundleDir.resolve(TinyCcBundle.MANIFEST_FILE_NAME), String.join("\n", lines) + "\n");
    }

    /// BundleSource backed by a fixture directory: copies the tree and reports honest hashes,
    /// like a local mirror of the classpath source.
    private static TinyCcBundle.BundleSource directorySource(Path fixtureDir) {
        return stagingDir -> {
            var manifest = new LinkedHashMap<String, String>();
            try (var walk = Files.walk(fixtureDir)) {
                for (var source : walk.filter(Files::isRegularFile).sorted().toList()) {
                    var relative = fixtureDir.relativize(source).toString().replace(File.separatorChar, '/');
                    var target = stagingDir.resolve(relative);
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(source, target);
                    manifest.put(relative, sha256Hex(target));
                }
            }
            return manifest;
        };
    }

    private static TinyCcBundle.BundleSource failingSource() {
        return stagingDir -> {
            throw new IOException("this source must never be consulted");
        };
    }

    /// Blocks the caller until the latch opens; used to park an installer inside its source
    /// while it holds the locks, making contention deterministic.
    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IOException("test gate timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test gate interrupted", exception);
        }
    }

    /// Waits until another thread is observably queued on the install lock — the deterministic
    /// contention signal that replaces any sleep-and-hope.
    private static void awaitLockContention(Path lockFile) throws InterruptedException {
        var lock = TinyCcBundle.requireInstallLock(lockFile);
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!lock.hasQueuedThreads()) {
            if (System.nanoTime() > deadline) {
                fail("the second installer never queued on the install lock");
            }
            Thread.sleep(5);
        }
    }

    /// Runs the lock probe as a child process and asserts what it sees. A separate JVM is
    /// required: within one JVM, `tryLock` consults the JVM lock table and cannot observe the
    /// OS-level lock at all. Output is drained on a companion thread so a hung child hits the
    /// wait timeout (not a silent read block) and is destroyed in the finally path.
    private static void assertLockProbeReports(Path lockFile, String expected) throws IOException, InterruptedException {
        var process = new ProcessBuilder(
                javaBin(), "-cp", System.getProperty("java.class.path"),
                TinyCcBundleLockProbe.class.getName(), lockFile.toString())
                .redirectErrorStream(true)
                .start();
        var output = new AtomicReference<String>();
        var reader = Thread.ofVirtual().start(() -> {
            try {
                output.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The stream closes when the process is destroyed on timeout.
            }
        });
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the lock probe hung");
            reader.join(Duration.ofSeconds(10));
            assertEquals(0, process.exitValue(), () -> "the lock probe failed: " + output.get());
            var actual = output.get();
            assertEquals(expected, actual == null ? null : actual.trim(), () -> "probe report for " + lockFile);
        } finally {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            reader.join(Duration.ofSeconds(10));
        }
    }

    /// Re-tags a fixture bundle's COPYING content and regenerates its MANIFEST so the tree
    /// stays self-consistent — competing bundles are distinguished by manifest-covered content,
    /// never by unmanifested marker files (full coverage is part of the integrity contract).
    private static void tagFixture(Path dir, String tag) throws IOException {
        Files.writeString(dir.resolve("COPYING"), tag + "\n");
        writeManifest(dir);
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /// Starts a child process that holds the given lock file for up to 60 seconds.
    private static Process startLockHolder(Path lockFile) throws IOException {
        return new ProcessBuilder(
                javaBin(), "-cp", System.getProperty("java.class.path"),
                TinyCcBundleLockProbe.class.getName(), "HOLD", lockFile.toString(), "60000")
                .redirectErrorStream(true)
                .start();
    }

    /// Polls the OS lock state from THIS process until the holder process has acquired it —
    /// the deterministic ready signal for cross-process lock tests (no fixed sleeps).
    private static void awaitLockHeldByAnotherProcess(Path lockFile) throws IOException, InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) {
                    return;
                }
                // We acquired it: the child is not there yet. try-with-resources releases and
                // the loop retries.
            }
            if (System.nanoTime() > deadline) {
                fail("the lock holder process never acquired the lock");
            }
            Thread.sleep(10);
        }
    }

    /// Waits until the waiter thread holds the in-JVM lock AND sits in the retry backoff of
    /// the cross-process file lock — observable as TIMED_WAITING, the only timed wait inside
    /// that lock region. This proves at least one failed lock attempt happened, so the
    /// subsequent interrupt exercises genuine mid-wait cancellation.
    private static void awaitCrossProcessLockBackoff(Thread waiter, Path lockFile) throws InterruptedException {
        var lock = TinyCcBundle.requireInstallLock(lockFile);
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!lock.isLocked() || waiter.getState() != Thread.State.TIMED_WAITING) {
            if (!waiter.isAlive() || System.nanoTime() > deadline) {
                fail("the waiter never entered the file-lock retry backoff");
            }
            Thread.sleep(5);
        }
    }

    private static List<Path> listStagingDirs(Path cacheRoot) throws IOException {
        try (var entries = Files.list(cacheRoot)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(".staging-")).sorted().toList();
        }
    }

    private static void assertNoStagingResidue(Path cacheRoot) throws IOException {
        var residue = listStagingDirs(cacheRoot);
        assertEquals(List.of(), residue, () -> "staging directories must not survive resolution in " + cacheRoot);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is a required algorithm of every Java runtime", exception);
        }
    }
}
