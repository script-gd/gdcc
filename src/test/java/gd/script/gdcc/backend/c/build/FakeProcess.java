package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Controlled fake [Process] for cancellation-protocol tests. Lifecycle modes:
/// - non-blocking: already terminated at construction, `waitFor` returns the configured exit
///   code immediately and stdout is a plain in-memory stream;
/// - blocking: parks inside `waitFor` until `destroy()`/`destroyForcibly()` is called, like a
///   long-running zig compile killed mid-flight; stdout is a gated stream that only reaches EOF
///   when the process is destroyed (a real pipe dies with its process);
/// - orphan-pipe: like blocking, but stdout never reaches EOF even after destruction — the
///   `zig cc` wrapper is gone while an orphaned grandchild (clang) still holds the pipe open.
///
/// Destroy calls are recorded so tests can assert the exact destruction contract.
public class FakeProcess extends Process {
    private final int exitCode;
    private final byte[] output;
    private final boolean blockUntilDestroyed;
    private final boolean orphanPipe;
    private final CountDownLatch exitLatch = new CountDownLatch(1);
    private final AtomicBoolean destroyCalled = new AtomicBoolean();
    private final AtomicBoolean destroyForciblyCalled = new AtomicBoolean();

    public FakeProcess(int exitCode, @NotNull String output) {
        this(exitCode, output, false);
    }

    public FakeProcess(int exitCode, @NotNull String output, boolean blockUntilDestroyed) {
        this(exitCode, output, blockUntilDestroyed, false);
    }

    public FakeProcess(int exitCode, @NotNull String output, boolean blockUntilDestroyed, boolean orphanPipe) {
        this.exitCode = exitCode;
        this.output = output.getBytes(StandardCharsets.UTF_8);
        this.blockUntilDestroyed = blockUntilDestroyed;
        this.orphanPipe = orphanPipe;
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        if (orphanPipe) {
            return new NeverEofInputStream();
        }
        if (blockUntilDestroyed) {
            return new GatedInputStream(exitLatch);
        }
        return new ByteArrayInputStream(output);
    }

    @Override
    public InputStream getErrorStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() throws InterruptedException {
        if (blockUntilDestroyed) {
            exitLatch.await();
        }
        return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        if (!blockUntilDestroyed) {
            return true;
        }
        return exitLatch.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (isAlive()) {
            throw new IllegalThreadStateException("fake process is still alive");
        }
        return exitCode;
    }

    @Override
    public void destroy() {
        destroyCalled.set(true);
        exitLatch.countDown();
    }

    @Override
    public Process destroyForcibly() {
        destroyForciblyCalled.set(true);
        exitLatch.countDown();
        return this;
    }

    @Override
    public boolean isAlive() {
        return blockUntilDestroyed && exitLatch.getCount() > 0;
    }

    public boolean destroyCalled() {
        return destroyCalled.get();
    }

    public boolean destroyForciblyCalled() {
        return destroyForciblyCalled.get();
    }

    /// EOFs only once the process was destroyed, like a pipe whose write end dies with the
    /// process.
    private static final class GatedInputStream extends InputStream {
        private final CountDownLatch gate;

        private GatedInputStream(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public int read() {
            try {
                gate.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /// Never yields data nor EOF: the destroyed wrapper left an orphaned grandchild holding the
    /// pipe. Responds to thread interrupt with an [IOException] so an interrupted drain exits.
    private static final class NeverEofInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            while (true) {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading the orphaned pipe", exception);
                }
            }
        }
    }
}
