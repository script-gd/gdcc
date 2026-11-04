package gd.script.gdcc.cli;

import gd.script.gdcc.util.FileLockGuard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/// Helper main for cross-process lock tests: takes the given lock file, prints `LOCK_ACQUIRED`
/// once held, then sleeps so the parent test can run a second CLI against the held lock. Prints
/// `LOCK_BUSY` and exits 3 when the lock was already taken — proving a lock is genuinely held
/// across processes.
public final class LockHolderProcess {
    public static final String ACQUIRED_MARKER = "LOCK_ACQUIRED";
    public static final String BUSY_MARKER = "LOCK_BUSY";

    private LockHolderProcess() {
    }

    /// Spawns the holder in a real second JVM on the current classpath.
    public static Process spawn(Path lockFile) throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                LockHolderProcess.class.getName(),
                lockFile.toString()
        ).redirectErrorStream(true).start();
    }

    /// First stdout line of the holder — either marker — bounded so a broken spawn fails fast.
    public static String awaitMarker(Process holder) {
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine();
                    } catch (IOException e) {
                        return null;
                    }
                })
                .orTimeout(30, TimeUnit.SECONDS)
                .join();
    }

    public static void main(String[] args) throws Exception {
        try (var ignored = FileLockGuard.tryAcquire(Path.of(args[0]))) {
            if (ignored == null) {
                System.out.println(BUSY_MARKER);
                System.out.flush();
                System.exit(3);
            }
            System.out.println(ACQUIRED_MARKER);
            System.out.flush();
            Thread.sleep(TimeUnit.MINUTES.toMillis(2));
        }
    }
}
