package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessUtilTest {
    @Test
    void waitForInterruptiblyDestroysProcessAndInterruptsDependentThread() {
        var process = new InterruptingProcess(1);
        var dependentThread = new Thread(() -> {
        });

        try {
            assertThrows(InterruptedException.class, () -> ProcessUtil.waitForInterruptibly(process, dependentThread));

            assertEquals(2, process.waitForCallCount);
            assertTrue(process.destroyedForcibly);
            assertTrue(dependentThread.isInterrupted());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                assertTrue(Thread.interrupted());
            }
        }
    }

    @Test
    void waitForInterruptiblyPreservesCleanupWhenPostDestroyWaitIsInterrupted() {
        var process = new InterruptingProcess(2);
        var dependentThread = new Thread(() -> {
        });

        try {
            var exception = assertThrows(
                    InterruptedException.class,
                    () -> ProcessUtil.waitForInterruptibly(process, dependentThread)
            );

            assertEquals("test interrupt 1", exception.getMessage());
            assertEquals(2, process.waitForCallCount);
            assertTrue(process.destroyedForcibly);
            assertTrue(dependentThread.isInterrupted());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                assertTrue(Thread.interrupted());
            }
        }
    }

    @Test
    void joinThreadAfterInterruptRestoresCallerInterruptStatus() throws InterruptedException {
        var joinedThread = new Thread(() -> {
        });
        joinedThread.start();
        joinedThread.join();

        try {
            Thread.currentThread().interrupt();
            ProcessUtil.joinThreadAfterInterrupt(joinedThread, Duration.ofMillis(1));

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                assertTrue(Thread.interrupted());
            }
        }
    }

    private static final class InterruptingProcess extends Process {
        private final int interruptedWaits;
        private int waitForCallCount;
        private boolean destroyedForcibly;

        private InterruptingProcess(int interruptedWaits) {
            this.interruptedWaits = interruptedWaits;
        }

        @Override
        public @NotNull OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public @NotNull InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public @NotNull InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            waitForCallCount++;
            if (waitForCallCount <= interruptedWaits) {
                throw new InterruptedException("test interrupt " + waitForCallCount);
            }
            return 143;
        }

        @Override
        public int exitValue() {
            return 143;
        }

        @Override
        public void destroy() {
        }

        @Override
        public @NotNull Process destroyForcibly() {
            destroyedForcibly = true;
            return this;
        }
    }
}
