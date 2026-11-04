package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/// An exclusive OS-level lock held open on a lock file until this guard is closed. Unlike the
/// in-JVM `CProjectBuildLock`, this coordinates separate gdcc processes contending for the same
/// directory.
///
/// Locks are advisory and released automatically if the owning process dies, so a crashed run can
/// never strand a permanently locked directory. The lock file itself is a stable marker — it is
/// created when missing and intentionally left behind after release.
///
/// Same-JVM re-acquisition is intercepted by a registry BEFORE another channel is opened: with
/// POSIX process-associated locks, closing any descriptor for a file drops every lock the process
/// holds on it, so a failed second `tryLock` must never get as far as opening and closing a
/// channel or it would silently release the first guard's lock.
public final class FileLockGuard implements AutoCloseable {
    /// Real paths of lock files this JVM currently holds; guards the same-process shortcut.
    private static final @NotNull Set<Path> JVM_HELD = new HashSet<>();

    private final @NotNull FileChannel channel;
    private final @NotNull FileLock lock;
    private final @NotNull Path registryKey;
    private boolean closed;

    private FileLockGuard(@NotNull FileChannel channel, @NotNull FileLock lock, @NotNull Path registryKey) {
        this.channel = channel;
        this.lock = lock;
        this.registryKey = registryKey;
    }

    /// Tries to take the lock without blocking; returns null when another process or this JVM
    /// already holds it. The lock file (and its parent directories) are created as needed.
    public static @Nullable FileLockGuard tryAcquire(@NotNull Path lockFile) throws IOException {
        var parent = lockFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Materialize the file first so the registry key is the real path — different spellings
        // of one lock file resolve to the same entry.
        try {
            Files.createFile(lockFile);
        } catch (FileAlreadyExistsException _) {
            // Already present; nothing to do.
        }
        var registryKey = lockFile.toRealPath();
        synchronized (JVM_HELD) {
            if (!JVM_HELD.add(registryKey)) {
                return null;
            }
        }
        // Every failure path must unregister before propagating, or a transient error (e.g. an
        // open failure) would poison the registry and block this JVM from locking the file ever
        // again. Exactly one finally owns that cleanup: unregistering twice could erase a
        // different thread's fresh registration for the same file.
        FileChannel channel = null;
        var acquired = false;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
            var lock = channel.tryLock();
            if (lock == null) {
                return null;
            }
            acquired = true;
            return new FileLockGuard(channel, lock, registryKey);
        } catch (OverlappingFileLockException e) {
            return null;
        } finally {
            if (!acquired) {
                // Close before unregistering: while this stray channel still exists, no other
                // thread may pass the registry or its later close could release that thread's
                // POSIX process-associated lock.
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } finally {
                    unregister(registryKey);
                }
            }
        }
    }

    private static void unregister(@NotNull Path registryKey) {
        synchronized (JVM_HELD) {
            JVM_HELD.remove(registryKey);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            unregister(registryKey);
        }
    }
}
