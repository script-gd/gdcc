package dev.superice.gdcc.util;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessUtilTest {
    @Test
    void waitForInterruptiblyDestroysProcessAndInterruptsDependentThread() {
        var process = new InterruptingProcess();
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

    private static final class InterruptingProcess extends Process {
        private int waitForCallCount;
        private boolean destroyedForcibly;

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
            if (waitForCallCount == 1) {
                throw new InterruptedException("test interrupt");
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
