package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Gate for the in-process per-project-directory build lock: concurrent `compile(...)` rounds
/// on one project directory must be serialized (their objects share the
/// `obj/<opt>/<target>/<index>_<file>.o` shape even across different output names), a round
/// cancelled while waiting for the lock must surface through the standard interrupt channel,
/// and textual path variants of one directory must share a single lock.
public class ZigCcCompilerProjectLockTest {

    @Test
    public void projectBuildLockIsSharedPerNormalizedDirectory() {
        var projectDir = Path.of("proj").toAbsolutePath();
        var lock = ZigCcCompiler.requireProjectBuildLock(projectDir);
        assertSame(lock, ZigCcCompiler.requireProjectBuildLock(projectDir.resolve("sub").resolve("..")),
                "textual variants of one directory must share the lock");
        assertNotSame(lock, ZigCcCompiler.requireProjectBuildLock(projectDir.resolve("other")),
                "different directories get independent locks");
    }

    @Test
    public void concurrentRoundsOnSameProjectDirAreSerializedAndKeepOwnSymbols(@TempDir Path tempDir) throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping project lock concurrency test");
            return;
        }
        var nm = findNm();
        if (nm == null || !isElfHost()) {
            Assumptions.abort("llvm-nm/nm on an ELF host is required for the per-artifact symbol check");
            return;
        }
        // Two rounds share one projectDir and the index-0 TU file name "entry.c" but carry
        // different source content and different output names: without serialization the rounds
        // would overwrite each other's 0_entry.c.o and cross-link the other round's symbol.
        // (A race can only be anchored statistically — with the lock the outcome is deterministic.)
        var sourceA = Files.createDirectories(tempDir.resolve("src_a")).resolve("entry.c");
        var sourceB = Files.createDirectories(tempDir.resolve("src_b")).resolve("entry.c");
        Files.writeString(sourceA, "__attribute__((visibility(\"default\"))) int symbol_a(void) { return 1; }\n");
        Files.writeString(sourceB, "__attribute__((visibility(\"default\"))) int symbol_b(void) { return 2; }\n");
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var targetPlatform = TargetPlatform.getNativePlatform();
        var compiler = new ZigCcCompiler();

        // Run on a dedicated two-thread pool: the common pool may have parallelism 1 on small
        // CI hosts, which would serialize the rounds even without the lock and void the teeth.
        // The timeout turns a broken lock (never released) into a failure instead of a hang.
        var executor = Executors.newFixedThreadPool(2);
        final CCompileResult resultA;
        final CCompileResult resultB;
        try {
            var roundA = CompletableFuture.supplyAsync(() ->
                    compiler.compile(projectDir, List.of(), List.of(sourceA), "probe_a", COptimizationLevel.DEBUG, targetPlatform), executor);
            var roundB = CompletableFuture.supplyAsync(() ->
                    compiler.compile(projectDir, List.of(), List.of(sourceB), "probe_b", COptimizationLevel.DEBUG, targetPlatform), executor);
            resultA = roundA.get(2, TimeUnit.MINUTES);
            resultB = roundB.get(2, TimeUnit.MINUTES);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(resultA.success(), () -> "round A failed:\n" + resultA.buildLog());
        assertTrue(resultB.success(), () -> "round B failed:\n" + resultB.buildLog());
        var symbolsA = dumpDynamicDefinedSymbols(nm, resultA.artifacts().getFirst());
        var symbolsB = dumpDynamicDefinedSymbols(nm, resultB.artifacts().getFirst());
        assertTrue(symbolsA.lines().anyMatch(line -> line.endsWith(" T symbol_a")), symbolsA);
        assertFalse(symbolsA.contains("symbol_b"), () -> "round A linked round B's object:\n" + symbolsA);
        assertTrue(symbolsB.lines().anyMatch(line -> line.endsWith(" T symbol_b")), symbolsB);
        assertFalse(symbolsB.contains("symbol_a"), () -> "round B linked round A's object:\n" + symbolsB);
    }

    @Test
    public void interruptWhileWaitingForProjectLockAbortsTheWaitingRound(@TempDir Path tempDir) throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping project lock interrupt test");
            return;
        }
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var probe = projectDir.resolve("probe.c");
        Files.writeString(probe, "int gdcc_probe(void) { return 1; }\n");
        // Hold the project lock directly so the waiting round provably blocks on it — no
        // dependence on a slow real compile for timing.
        var lock = ZigCcCompiler.requireProjectBuildLock(projectDir);
        lock.lock();
        var waiterResult = new AtomicReference<@Nullable CCompileResult>();
        var compiler = new ZigCcCompiler();
        var targetPlatform = TargetPlatform.getNativePlatform();
        var waiter = Thread.ofPlatform().name("gdcc-test-lock-waiter").start(() ->
                waiterResult.set(compiler.compile(projectDir, List.of(), List.of(probe), "probe", COptimizationLevel.DEBUG, targetPlatform)));
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!lock.hasQueuedThreads()) {
                if (System.nanoTime() > deadline) {
                    fail("the waiting round never blocked on the project build lock");
                }
                Thread.sleep(10);
            }
            waiter.interrupt();
            waiter.join(Duration.ofSeconds(10));
            assertFalse(waiter.isAlive(), "the waiting round must return after the interrupt");
            var result = waiterResult.get();
            assertNotNull(result);
            assertFalse(result.success());
            assertTrue(result.artifacts().isEmpty());
            assertEquals("Failed to run zig: interrupted", result.buildLog());
        } finally {
            lock.unlock();
            waiter.join(Duration.ofSeconds(10));
        }
        // The lock stays usable: a later round on the same directory compiles normally.
        var result = compiler.compile(projectDir, List.of(), List.of(probe), "probe", COptimizationLevel.DEBUG, targetPlatform);
        assertTrue(result.success(), () -> "round after lock release failed:\n" + result.buildLog());
    }

    /// Prefers `llvm-nm` over binutils `nm`; both accept the GNU-style flags used below.
    private static @Nullable String findNm() {
        for (var candidate : List.of("llvm-nm", "nm")) {
            try {
                var probe = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                probe.getInputStream().readAllBytes();
                if (probe.waitFor() == 0) {
                    return candidate;
                }
            } catch (IOException exception) {
                // Candidate not installed; try the next one.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static boolean isElfHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static @NotNull String dumpDynamicDefinedSymbols(@NotNull String nm, @NotNull Path library) throws IOException {
        try {
            var dump = new ProcessBuilder(nm, "-D", "--defined-only", library.toString()).redirectErrorStream(true).start();
            var output = new String(dump.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (dump.waitFor() != 0) {
                throw new IOException("nm failed on " + library + ":\n" + output);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while dumping symbols of " + library);
        }
    }
}
