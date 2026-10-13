package gd.script.gdcc.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileUtilTest {
    @Test
    public void retrySucceedsAfterTransientAccessDenied() throws IOException {
        var attempts = new AtomicInteger();

        var result = FileUtil.retryOnAccessDenied(
                () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new AccessDeniedException("locked");
                    }
                    return "ok";
                },
                5,
                1
        );

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    public void retryExhaustionRethrowsLastAccessDenied() {
        var attempts = new AtomicInteger();

        assertThrows(
                AccessDeniedException.class,
                () -> FileUtil.retryOnAccessDenied(
                        () -> {
                            attempts.incrementAndGet();
                            throw new AccessDeniedException("locked");
                        },
                        3,
                        1
                )
        );
        assertEquals(3, attempts.get());
    }

    @Test
    public void otherIoExceptionsPropagateImmediatelyWithoutRetry() {
        var attempts = new AtomicInteger();

        assertThrows(
                NoSuchFileException.class,
                () -> FileUtil.retryOnAccessDenied(
                        () -> {
                            attempts.incrementAndGet();
                            throw new NoSuchFileException("missing");
                        },
                        5,
                        1
                )
        );
        assertEquals(1, attempts.get());
    }

    @Test
    public void runWithAccessDeniedRetryExecutesVoidOperation() throws IOException {
        var executions = new AtomicInteger();

        FileUtil.runWithAccessDeniedRetry(executions::incrementAndGet);

        assertEquals(1, executions.get());
    }

    @Test
    public void interruptDuringBackoffFailsFastAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    IOException.class,
                    () -> FileUtil.retryOnAccessDenied(
                            () -> {
                                throw new AccessDeniedException("locked");
                            },
                            5,
                            60_000
                    )
            );
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Do not leak the interrupt flag into the shared JUnit worker thread.
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
    }

    @Test
    public void moveReplacingReplacesExistingTarget(@TempDir Path dir) throws IOException {
        var target = dir.resolve("out.txt");
        var source = dir.resolve("staged.tmp");
        Files.writeString(target, "old");
        Files.writeString(source, "new");

        FileUtil.moveReplacing(source, target);

        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(source));
    }
}
