package gd.script.gdcc.backend.c.build;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Single-purpose probe PROCESS used by [TinyCcBundleTest] to observe and hold the OS-level
/// state of a bundle install lock file from OUTSIDE the test JVM. An in-JVM `tryLock` cannot
/// serve the observation purpose: the JVM keeps its own file-lock table and reports in-JVM
/// conflicts without consulting the OS, so it can never notice a POSIX lock that was silently
/// released by closing another descriptor of the same file. A child process sees the truth.
///
/// Usage:
/// - `java TinyCcBundleLockProbe <lockFile>` — prints `HELD` when another process holds the
///   lock, `ACQUIRED` when the probe could take it; exit code is 0 in both cases.
/// - `java TinyCcBundleLockProbe HOLD <lockFile> <millis>` — takes the lock (blocking), prints
///   `ACQUIRED` once held, then keeps holding for the given time or until killed.
public final class TinyCcBundleLockProbe {
    private TinyCcBundleLockProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("HOLD")) {
            try (var channel = FileChannel.open(Path.of(args[1]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                System.out.println("ACQUIRED");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[2]));
            }
            return;
        }
        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            // tryLock returns null when ANOTHER process holds the lock.
            System.out.println(lock == null ? "HELD" : "ACQUIRED");
        }
    }
}
