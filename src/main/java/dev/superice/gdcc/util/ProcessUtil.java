package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

public final class ProcessUtil {
    private ProcessUtil() {
    }

    /// Waits for a child process while preserving cancellation semantics for the caller thread.
    /// If the caller is interrupted, the child process is forcibly destroyed and the dependent
    /// reader thread is interrupted so pipe-draining work can stop as well.
    public static int waitForInterruptibly(
            @NotNull Process process,
            @NotNull Thread dependentThread
    ) throws InterruptedException {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            try {
                process.destroyForcibly();
                try {
                    process.waitFor();
                } catch (InterruptedException _) {
                    // Preserve the original cancellation while still running cleanup below.
                }
            } finally {
                dependentThread.interrupt();
                Thread.currentThread().interrupt();
            }
            throw exception;
        }
    }

    /// Joins a thread after the caller has already been interrupted, then restores the caller's
    /// interrupt status so cancellation remains visible to upstream code.
    public static void joinThreadAfterInterrupt(
            @NotNull Thread thread,
            @NotNull Duration timeout
    ) throws InterruptedException {
        var restoreInterrupt = Thread.interrupted();
        try {
            thread.join(timeout);
        } catch (InterruptedException exception) {
            restoreInterrupt = true;
            throw exception;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
