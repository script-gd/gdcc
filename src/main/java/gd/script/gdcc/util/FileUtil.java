package gd.script.gdcc.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileUtil {
    private FileUtil() {
        // utility
    }

    /// Default retry budget for file operations that transiently fail with [AccessDeniedException].
    /// Matches the budget long used by the Godot test harness for Windows file-lock contention.
    public static final int ACCESS_DENIED_MAX_ATTEMPTS = 10;
    public static final long ACCESS_DENIED_BACKOFF_MILLIS = 500;

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }

    /// Runs [operation], retrying with a fixed backoff when it fails with [AccessDeniedException].
    ///
    /// On Windows, antivirus scans, search indexers, or a concurrent writer replacing the same path
    /// can transiently hold a handle without delete sharing, surfacing as access-denied even for an
    /// otherwise valid operation. Other [IOException]s propagate immediately, and the denial from the
    /// last attempt propagates once the retry budget is exhausted.
    public static <T> T retryOnAccessDenied(@NotNull IoSupplier<T> operation, int maxAttempts, long backoffMillis) throws IOException {
        var attemptsRemaining = maxAttempts;
        while (true) {
            try {
                return operation.get();
            } catch (AccessDeniedException e) {
                attemptsRemaining--;
                if (attemptsRemaining <= 0) {
                    throw e;
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while backing off after AccessDeniedException", interrupted);
                }
            }
        }
    }

    /// [IoRunnable] variant of [retryOnAccessDenied] with the default retry budget.
    public static void runWithAccessDeniedRetry(@NotNull IoRunnable operation) throws IOException {
        retryOnAccessDenied(
                () -> {
                    operation.run();
                    return null;
                },
                ACCESS_DENIED_MAX_ATTEMPTS,
                ACCESS_DENIED_BACKOFF_MILLIS
        );
    }

    /// Moves [source] onto [target], replacing any existing file and using an atomic rename when the
    /// filesystem supports it. The whole move runs under [retryOnAccessDenied] so transient Windows
    /// handle contention (antivirus, concurrent replace of a shared path) does not fail the caller.
    public static void moveReplacing(@NotNull Path source, @NotNull Path target) throws IOException {
        retryOnAccessDenied(
                () -> {
                    try {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return null;
                },
                ACCESS_DENIED_MAX_ATTEMPTS,
                ACCESS_DENIED_BACKOFF_MILLIS
        );
    }
}
