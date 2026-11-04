package gd.script.gdcc.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileLockGuardTest {
    @Test
    public void acquireCreatesLockFileAndNestedParents(@TempDir Path dir) throws IOException {
        var lockFile = dir.resolve("deep/nested/.gdcc.lock");

        try (var ignored = FileLockGuard.tryAcquire(lockFile)) {
            assertNotNull(ignored);
            assertTrue(Files.exists(lockFile));
        }
    }

    /// JVMs report a same-process double acquisition as [OverlappingFileLockException]; the guard
    /// surfaces that as contention instead of deadlocking on itself.
    @Test
    public void secondAcquireInSameJvmReportsContention(@TempDir Path dir) throws IOException {
        var lockFile = dir.resolve(".gdcc.lock");

        try (var first = FileLockGuard.tryAcquire(lockFile)) {
            assertNotNull(first);
            assertNull(FileLockGuard.tryAcquire(lockFile));
        }
    }

    @Test
    public void releasedLockCanBeReacquired(@TempDir Path dir) throws IOException {
        var lockFile = dir.resolve(".gdcc.lock");

        try (var ignored = FileLockGuard.tryAcquire(lockFile)) {
            assertNotNull(ignored);
        }
        try (var ignored = FileLockGuard.tryAcquire(lockFile)) {
            assertNotNull(ignored);
        }
    }

    /// Separate lock files do not contend — the granularity is per path, not per JVM.
    @Test
    public void distinctLockFilesDoNotContend(@TempDir Path dir) throws IOException {
        try (var ignored = FileLockGuard.tryAcquire(dir.resolve("a.lock"));
             var second = FileLockGuard.tryAcquire(dir.resolve("b.lock"))) {
            assertNotNull(second);
        }
    }

    /// Regression for POSIX process-associated locks: a failed same-JVM acquisition must not
    /// close any extra descriptor for the file — closing one would drop the first guard's kernel
    /// lock and let a second process in.
    @Test
    public void failedSameJvmReacquisitionKeepsTheOsLockHeld(@TempDir Path dir) throws Exception {
        var lockFile = dir.resolve(".gdcc.lock");

        try (var ignored = FileLockGuard.tryAcquire(lockFile)) {
            assertNotNull(ignored);
            assertNull(FileLockGuard.tryAcquire(lockFile));

            var holder = gd.script.gdcc.cli.LockHolderProcess.spawn(lockFile);
            try {
                assertEquals(gd.script.gdcc.cli.LockHolderProcess.BUSY_MARKER,
                        gd.script.gdcc.cli.LockHolderProcess.awaitMarker(holder));
                assertEquals(3, holder.waitFor());
            } finally {
                holder.destroyForcibly();
            }
        }

        // Only after the real release may a second process take the lock.
        var holder = gd.script.gdcc.cli.LockHolderProcess.spawn(lockFile);
        try {
            assertEquals(gd.script.gdcc.cli.LockHolderProcess.ACQUIRED_MARKER,
                    gd.script.gdcc.cli.LockHolderProcess.awaitMarker(holder));
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    /// Regression for registry poisoning: when opening the channel fails (a directory squats on
    /// the lock path), the same-JVM registry entry must be released — otherwise this JVM could
    /// never lock that file again even after the obstruction is gone.
    @Test
    public void failedAcquisitionDoesNotPoisonTheRegistry(@TempDir Path dir) throws IOException {
        var lockFile = dir.resolve(".gdcc.lock");
        Files.createDirectory(lockFile);

        assertThrows(IOException.class, () -> FileLockGuard.tryAcquire(lockFile));

        Files.delete(lockFile);
        try (var guard = FileLockGuard.tryAcquire(lockFile)) {
            assertNotNull(guard);
        }
    }
}
