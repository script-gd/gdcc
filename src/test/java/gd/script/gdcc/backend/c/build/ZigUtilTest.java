package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pure-Java gate for [ZigUtil.findZigVersion]: a [FakeProcessLauncher] drives the probe
/// through the package-private process-manager overload — no real zig binary is needed.
/// Anchors the layered contract: only a successful parse is cached (failures and interrupts
/// re-probe), an undeterminable version yields `null` (never cached as "zig missing"), and an
/// interrupt destroys the probe child, converges promptly, and propagates as
/// [InterruptedException] instead of collapsing into `null`.
class ZigUtilTest {

    @BeforeEach
    void resetVersionCache() {
        ZigUtil.clearCachedZigVersionForTesting();
    }

    @org.junit.jupiter.api.AfterEach
    void clearVersionCacheAfter() {
        // A cached fake version must not leak into later real-zig tests of the same worker.
        ZigUtil.clearCachedZigVersionForTesting();
    }

    @Test
    void successfulProbeIsParsedAndCached(@TempDir Path tempDir) throws InterruptedException {
        var launcher = new FakeProcessLauncher().outputProvider(cmd -> "0.16.0\n");
        var registry = new CProcessRegistry();

        var version = ZigUtil.findZigVersion(Path.of("zig"), launcher, registry, tempDir, Map.of());

        assertEquals("0.16.0", version);
        assertEquals(1, launcher.startedProcesses().size(), "one probe process");
        // Second call: cached, no further process is started.
        assertEquals("0.16.0", ZigUtil.findZigVersion(Path.of("zig"), launcher, registry, tempDir, Map.of()));
        assertEquals(1, launcher.startedProcesses().size(), "a cached version never re-probes");
    }

    @Test
    void failedProbeReturnsNullAndIsNotCached(@TempDir Path tempDir) throws InterruptedException {
        var launcher = new FakeProcessLauncher().failCommandsWithExitOne(cmd -> true);
        var registry = new CProcessRegistry();

        assertNull(ZigUtil.findZigVersion(Path.of("zig"), launcher, registry, tempDir, Map.of()),
                "a non-zero probe exit yields null, not an exception");

        // The failure is not cached: a later healthy probe succeeds on the next call.
        var healthy = new FakeProcessLauncher().outputProvider(cmd -> "0.16.1\n");
        assertEquals("0.16.1", ZigUtil.findZigVersion(Path.of("zig"), healthy, registry, tempDir, Map.of()));
        assertEquals(1, healthy.startedProcesses().size(), "failure did not suppress the re-probe");
    }

    @Test
    void blankOutputReturnsNullAndIsNotCached(@TempDir Path tempDir) throws InterruptedException {
        var launcher = new FakeProcessLauncher().outputProvider(cmd -> "  \n\n");
        var registry = new CProcessRegistry();

        assertNull(ZigUtil.findZigVersion(Path.of("zig"), launcher, registry, tempDir, Map.of()));
        assertEquals("0.16.2", ZigUtil.findZigVersion(Path.of("zig"),
                new FakeProcessLauncher().outputProvider(cmd -> "0.16.2\n"), registry, tempDir, Map.of()));
    }

    @Test
    void interruptDestroysTheProbeAndPropagates(@TempDir Path tempDir) throws Exception {
        var launcher = new FakeProcessLauncher().blockProcessesUntilDestroyed();
        var registry = new CProcessRegistry();
        var failure = new AtomicReference<Throwable>();
        var interruptRestored = new AtomicBoolean();
        var probeThread = Thread.ofVirtual().name("gdcc-test-version-probe").start(() -> {
            try {
                ZigUtil.findZigVersion(Path.of("zig"), launcher, registry, tempDir, Map.of());
            } catch (InterruptedException exception) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
                failure.set(exception);
            }
        });

        launcher.awaitFirstProcess();
        probeThread.interrupt();
        probeThread.join(Duration.ofSeconds(30));
        assertFalse(probeThread.isAlive(), "a cancelled probe must converge promptly");

        assertInstanceOf(InterruptedException.class, failure.get(),
                "the cancel channel is InterruptedException, never a null downgrade");
        assertTrue(interruptRestored.get(), "the interrupt status is restored for the caller");
        assertTrue(launcher.startedProcesses().stream().allMatch(FakeProcess::destroyForciblyCalled),
                "the probe child is forcibly destroyed");
        // The interrupt is not cached either: a later healthy probe still works.
        assertEquals("0.16.3", ZigUtil.findZigVersion(Path.of("zig"),
                new FakeProcessLauncher().outputProvider(cmd -> "0.16.3\n"), new CProcessRegistry(), tempDir, Map.of()));
    }

    @Test
    void startFailureReturnsNullWithoutAProcess(@TempDir Path tempDir) throws InterruptedException {
        var launcher = new FakeProcessLauncher().failStartWith(cmd -> true);
        assertNull(ZigUtil.findZigVersion(Path.of("zig"), launcher, new CProcessRegistry(), tempDir, Map.of()));
        assertTrue(launcher.startedProcesses().isEmpty(), "a spawn failure is a null probe, not a registered process");
    }
}
