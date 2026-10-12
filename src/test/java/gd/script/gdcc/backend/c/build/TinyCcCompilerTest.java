package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// End-to-end behavior of [TinyCcCompiler] against the real libtcc, gated on a resolvable
/// bundle (see [TinyCcCliTestSupport#findTinyCcBundleRoot]). Fixture C sources are written
/// inline and do not depend on the generator's output. Anchored contracts: multiple translation
/// units merge-compile into one shared library exporting `gdextension_entry` (verified with
/// `nm -D` on ELF hosts), diagnostics pass through with their native `file:line: error:` shape,
/// a failed round never poisons the next one (fresh state per round), the interrupted channel is
/// fixed, and a stale artifact is replaced by a successful round rather than masking failures.
class TinyCcCompilerTest {

    private static Path bundleRoot;

    @BeforeAll
    static void requireBundle() {
        bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null,
                "no tinycc bundle available (GDCC_TINYCC_HOME unset, no build/tinycc-bundle); skipping TinyCcCompiler tests");
    }

    private static @NotNull TinyCcCompiler newCompiler() {
        return new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create);
    }

    @Test
    void compilesMultipleTranslationUnitsIntoOneSharedLibrary(@TempDir Path tempDir) throws IOException {
        var includeDir = Files.createDirectories(tempDir.resolve("inc"));
        Files.writeString(includeDir.resolve("gdcc_probe.h"), "#define GDCC_PROBE_ANSWER 42\n");
        // Two TUs with a cross-TU call prove the merged single-state compilation (no object
        // phase): tu_b references a symbol defined by tu_a, and the include dir comes only from
        // the round's include paths.
        var tuA = tempDir.resolve("tu_a.c");
        Files.writeString(tuA, """
                #include <gdcc_probe.h>
                int gdcc_probe_a(void) { return GDCC_PROBE_ANSWER; }
                """);
        var tuB = tempDir.resolve("tu_b.c");
        Files.writeString(tuB, """
                int gdcc_probe_a(void);
                int gdextension_entry(void) { return gdcc_probe_a(); }
                """);

        var host = TargetPlatform.getNativePlatform();
        var result = newCompiler().compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "compilation failed:\n" + result.buildLog());
        assertEquals("", result.buildLog(), "a clean round must not produce diagnostics");
        var artifact = tempDir.resolve(host.sharedLibraryFileName("probe")).toAbsolutePath();
        assertEquals(List.of(artifact), result.artifacts());
        assertTrue(Files.isRegularFile(artifact) && Files.size(artifact) > 0);
        NativeArtifactInspector.assertSharedLibraryShape(artifact);
        NativeArtifactInspector.assertExportsSymbol(artifact, "gdextension_entry");
    }

    @Test
    void syntaxErrorDiagnosticsPassThroughAndFailTheRound(@TempDir Path tempDir) throws IOException {
        var broken = tempDir.resolve("broken.c");
        Files.writeString(broken, "int broken(\n");

        var host = TargetPlatform.getNativePlatform();
        var result = newCompiler().compile(tempDir, List.of(), List.of(broken), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        // libtcc diagnostics carry their own file:line: error: prefix; they must reach buildLog
        // unmodified, followed by the failure channel.
        assertTrue(result.buildLog().contains("broken.c"), () -> "diagnostic must name the file:\n" + result.buildLog());
        assertTrue(result.buildLog().contains("error"), () -> "diagnostic must carry the error text:\n" + result.buildLog());
        assertTrue(result.buildLog().contains("Failed to run tcc: "), () -> "the failure channel must follow the diagnostics:\n" + result.buildLog());
        assertFalse(Files.exists(tempDir.resolve(host.sharedLibraryFileName("probe"))), "a failed round leaves no artifact");
    }

    @Test
    void aFailedRoundNeverPoisonsTheNextOne(@TempDir Path tempDir) throws IOException {
        var broken = tempDir.resolve("broken.c");
        Files.writeString(broken, "int broken(\n");
        var good = tempDir.resolve("good.c");
        Files.writeString(good, "int gdextension_entry(void) { return 1; }\n");
        var host = TargetPlatform.getNativePlatform();
        var compiler = newCompiler();

        var failed = compiler.compile(tempDir, List.of(), List.of(broken), "probe", COptimizationLevel.DEBUG, host);
        assertFalse(failed.success());
        var succeeded = compiler.compile(tempDir, List.of(), List.of(good), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(succeeded.success(), () -> "the round after a failure must start from a fresh state:\n" + succeeded.buildLog());
        assertTrue(Files.isRegularFile(succeeded.artifacts().getFirst()));
    }

    @Test
    void emptyCFilesFailFast(@TempDir Path tempDir) {
        var host = TargetPlatform.getNativePlatform();
        var result = newCompiler().compile(tempDir, List.of(), List.of(), "probe", COptimizationLevel.DEBUG, host);
        assertFalse(result.success());
        assertEquals("No C files to compile", result.buildLog());
        assertTrue(result.artifacts().isEmpty());
    }

    @Test
    void aStaleArtifactIsReplacedByASuccessfulRound(@TempDir Path tempDir) throws IOException {
        var good = tempDir.resolve("good.c");
        Files.writeString(good, "int gdextension_entry(void) { return 1; }\n");
        var host = TargetPlatform.getNativePlatform();
        var artifact = tempDir.resolve(host.sharedLibraryFileName("probe"));
        Files.writeString(artifact, "stale artifact from an earlier round");

        var result = newCompiler().compile(tempDir, List.of(), List.of(good), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "compilation failed:\n" + result.buildLog());
        NativeArtifactInspector.assertSharedLibraryShape(artifact);
    }

    @Test
    void interruptWhileWaitingForTheGlobalLockSurfacesTheFixedChannel(@TempDir Path tempDir) throws Exception {
        var good = tempDir.resolve("good.c");
        Files.writeString(good, "int gdextension_entry(void) { return 1; }\n");
        var host = TargetPlatform.getNativePlatform();
        var compiler = newCompiler();
        var globalLock = TinyCcCompiler.tccGlobalLock();
        globalLock.lock();
        var resultRef = new AtomicReference<@Nullable CCompileResult>();
        var interruptRestored = new AtomicBoolean();
        var waiter = Thread.ofPlatform().name("gdcc-test-tinycc-lock-waiter").unstarted(() -> {
            resultRef.set(compiler.compile(tempDir, List.of(), List.of(good), "probe", COptimizationLevel.DEBUG, host));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!globalLock.hasQueuedThreads()) {
                if (System.nanoTime() > deadline) {
                    fail("the waiting round never blocked on the tcc global lock");
                }
                if (!waiter.isAlive()) {
                    fail("the waiting round died before blocking on the tcc global lock");
                }
                Thread.sleep(10);
            }
            waiter.interrupt();
            waiter.join(Duration.ofSeconds(10));
            assertFalse(waiter.isAlive(), "the waiting round must converge after the interrupt");
            var result = resultRef.get();
            assertNotNull(result);
            assertFalse(result.success());
            assertEquals("Failed to run tcc: interrupted", result.buildLog());
            assertTrue(result.artifacts().isEmpty());
            assertTrue(interruptRestored.get(), "the interrupt status must be restored for the caller");
        } finally {
            globalLock.unlock();
            waiter.join(Duration.ofSeconds(10));
        }
        // The lock survives the cancelled round: a later round compiles normally.
        var followUp = compiler.compile(tempDir, List.of(), List.of(good), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(followUp.success(), () -> "round after lock release failed:\n" + followUp.buildLog());
    }
}
