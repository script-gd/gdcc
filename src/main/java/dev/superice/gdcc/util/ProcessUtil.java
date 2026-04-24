package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;

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
            process.destroyForcibly();
            process.waitFor();
            dependentThread.interrupt();
            Thread.currentThread().interrupt();
            throw exception;
        }
    }
}
