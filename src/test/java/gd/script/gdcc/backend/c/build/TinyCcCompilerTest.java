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
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        Files.writeString(tuB, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + """
                int gdcc_probe_a(void);
                GDCC_TEST_EXPORT int gdextension_entry(void) { return gdcc_probe_a(); }
                """);

        var host = TargetPlatform.getNativePlatform();
        var result = newCompiler().compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "compilation failed:\n" + result.buildLog());
        assertEquals("[gdcc] tcc object cache: 0 hits, 2 misses\n", result.buildLog(),
                "a clean cache-available round carries only the fixed statistics line");
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
        Files.writeString(good, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 1; }\n");
        var host = TargetPlatform.getNativePlatform();
        var compiler = newCompiler();

        var failed = compiler.compile(tempDir, List.of(), List.of(broken), "probe", COptimizationLevel.DEBUG, host);
        assertFalse(failed.success());
        var succeeded = compiler.compile(tempDir, List.of(), List.of(good), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(succeeded.success(), () -> "the round after a failure must start from a fresh state:\n" + succeeded.buildLog());
        assertTrue(Files.isRegularFile(succeeded.artifacts().getFirst()));
    }

    @Test
    void compilesWhenPathsContainSpacesAndShellMetacharacters(@TempDir Path tempDir) throws IOException {
        var host = TargetPlatform.getNativePlatform();
        var name = "spaces $() & 'single'" + (host == TargetPlatform.MACOS_AARCH64 ? " \"double\"" : "");
        var project = Files.createDirectories(tempDir.resolve(name));
        var source = project.resolve("probe.c");
        Files.writeString(source, NativeArtifactInspector.EXPORT_MACRO_DEFINITION
                + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 42; }\n");
        var result = newCompiler().compile(project, List.of(), List.of(source), name, COptimizationLevel.DEBUG, host);
        assertTrue(result.success(), result::buildLog);
        assertEquals(List.of(project.resolve(host.sharedLibraryFileName(name)).toAbsolutePath()), result.artifacts());
        NativeArtifactInspector.assertExportsSymbol(result.artifacts().getFirst(), "gdextension_entry");
    }

    @Test
    void codesignFailureReturnsWithoutTerminatingTheJvmAndTheNextRoundSucceeds(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(TargetPlatform.getNativePlatform() == TargetPlatform.MACOS_AARCH64);
        var project = Files.createDirectories(tempDir.resolve("spaces $() & 'single' \"double\""));
        var source = project.resolve("probe.c");
        Files.writeString(source, NativeArtifactInspector.EXPORT_MACRO_DEFINITION
                + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 42; }\n");
        var bin = Files.createDirectories(tempDir.resolve("bin"));
        var signer = bin.resolve("codesign");
        Files.writeString(signer, """
                #!/bin/sh
                if [ ! -e "$GDCC_TEST_CODESIGN_MARKER" ]; then
                    : > "$GDCC_TEST_CODESIGN_MARKER"
                    exit 7
                fi
                exec /usr/bin/codesign "$@"
                """);
        assertTrue(signer.toFile().setExecutable(true));
        var log = tempDir.resolve("child.log");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                CodesignProbe.class.getName(), bundleRoot.toString(), project.toString(), source.toString());
        builder.environment().put("PATH", bin + File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
        builder.environment().put("GDCC_TEST_CODESIGN_MARKER", tempDir.resolve("signer-called").toString());
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "signing recovery probe hung");
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("SIGNING_FAILURE_RECOVERED"), output);
            assertTrue(Files.isRegularFile(tempDir.resolve("signer-called")), "the injected signer was not called");
        } finally {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
        }
    }

    /// A separate JVM contains regressions that would otherwise exit the entire test worker.
    public static final class CodesignProbe {
        public static void main(String[] args) throws IOException {
            var root = Path.of(args[0]);
            var project = Path.of(args[1]);
            var source = Path.of(args[2]);
            var compiler = new TinyCcCompiler(() -> root, TinyCcFfmNative::create);
            var host = TargetPlatform.getNativePlatform();
            var failed = compiler.compile(project, List.of(), List.of(source), "probe", COptimizationLevel.DEBUG, host);
            assertFalse(failed.success(), failed::buildLog);
            assertTrue(failed.artifacts().isEmpty());
            assertTrue(failed.buildLog().contains("codesign"), failed::buildLog);
            assertTrue(failed.buildLog().contains("tcc_output_file failed"), failed::buildLog);
            var succeeded = compiler.compile(project, List.of(), List.of(source), "probe", COptimizationLevel.DEBUG, host);
            assertTrue(succeeded.success(), succeeded::buildLog);
            NativeArtifactInspector.assertExportsSymbol(succeeded.artifacts().getFirst(), "gdextension_entry");
            System.out.println("SIGNING_FAILURE_RECOVERED");
        }
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
        Files.writeString(good, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 1; }\n");
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
        Files.writeString(good, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 1; }\n");
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

    @Test
    void aSecondRoundHitsEveryUnchangedTu(@TempDir Path tempDir) throws IOException {
        var includeDir = Files.createDirectories(tempDir.resolve("inc"));
        Files.writeString(includeDir.resolve("gdcc_probe.h"), "#define GDCC_PROBE_ANSWER 42\n");
        var tuA = tempDir.resolve("tu_a.c");
        Files.writeString(tuA, """
                #include <gdcc_probe.h>
                int gdcc_probe_a(void) { return GDCC_PROBE_ANSWER; }
                """);
        var tuB = tempDir.resolve("tu_b.c");
        Files.writeString(tuB, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + """
                int gdcc_probe_a(void);
                GDCC_TEST_EXPORT int gdextension_entry(void) { return gdcc_probe_a(); }
                """);
        var host = TargetPlatform.getNativePlatform();
        var outputTypesPerRound = new java.util.ArrayList<List<Integer>>();
        var compiler = newCompilerWithSpy(outputTypesPerRound);

        var first = compiler.compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(first.success(), () -> "first round failed:\n" + first.buildLog());
        assertEquals("[gdcc] tcc object cache: 0 hits, 2 misses\n", first.buildLog());
        assertEquals(List.of(TinyCcLibrary.TCC_OUTPUT_OBJ, TinyCcLibrary.TCC_OUTPUT_OBJ, TinyCcLibrary.TCC_OUTPUT_DLL),
                outputTypesPerRound.getFirst(), "first round: two OBJ states plus one link state");
        // Snapshot before the second round rewrites the output path.
        var firstExports = NativeArtifactInspector.exportedSymbols(first.artifacts().getFirst());

        var second = compiler.compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(second.success(), () -> "second round failed:\n" + second.buildLog());
        assertEquals("[gdcc] tcc object cache: 2 hits, 0 misses\n", second.buildLog());
        assertEquals(List.of(TinyCcLibrary.TCC_OUTPUT_DLL), outputTypesPerRound.get(1),
                "a full-hit round creates only the link state");
        // The hit round's artifact exports exactly the same symbols as the miss round's.
        assertEquals(firstExports, NativeArtifactInspector.exportedSymbols(second.artifacts().getFirst()));
    }

    @Test
    void onlyTheChangedTuRecompiles(@TempDir Path tempDir) throws Throwable {
        // The answer lives in a TU-adjacent header reached via a quote-include: changing it must
        // invalidate tu_a's entry even though tu_a.c itself is untouched. tu_b lives in its own
        // directory — adjacent headers hash per TU directory, so an unrelated header change in
        // tu_a's directory must not invalidate tu_b.
        Files.writeString(tempDir.resolve("probe_value.h"), "#define GDCC_PROBE_ANSWER 42\n");
        var tuA = tempDir.resolve("tu_a.c");
        Files.writeString(tuA, """
                #include "probe_value.h"
                int gdcc_probe_a(void) { return GDCC_PROBE_ANSWER; }
                """);
        var tuBDir = Files.createDirectories(tempDir.resolve("sub"));
        var tuB = tuBDir.resolve("tu_b.c");
        Files.writeString(tuB, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + """
                int gdcc_probe_a(void);
                GDCC_TEST_EXPORT int gdextension_entry(void) { return gdcc_probe_a(); }
                """);
        var host = TargetPlatform.getNativePlatform();
        var outputTypesPerRound = new java.util.ArrayList<List<Integer>>();
        var compiler = newCompilerWithSpy(outputTypesPerRound);

        var first = compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(first.success(), () -> "first round failed:\n" + first.buildLog());
        assertEquals(42, callEntry(first.artifacts().getFirst()), "the miss round links the current sources");

        // Only the local header changes: tu_a's key must change, tu_b's must not.
        Files.writeString(tempDir.resolve("probe_value.h"), "#define GDCC_PROBE_ANSWER 43\n");
        var second = compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(second.success(), () -> "second round failed:\n" + second.buildLog());
        assertEquals("[gdcc] tcc object cache: 1 hits, 1 misses\n", second.buildLog());
        assertEquals(List.of(TinyCcLibrary.TCC_OUTPUT_OBJ, TinyCcLibrary.TCC_OUTPUT_DLL), outputTypesPerRound.get(1),
                "exactly one OBJ state for the changed TU, plus the link state");
        NativeArtifactInspector.assertExportsSymbol(second.artifacts().getFirst(), "gdextension_entry");
        assertEquals(43, callEntry(second.artifacts().getFirst()),
                "the relinked library runs the recompiled code, not the stale object");
    }

    @Test
    void debugAndReleaseRoundsShareCacheEntries(@TempDir Path tempDir) throws IOException {
        var tuA = tempDir.resolve("tu_a.c");
        Files.writeString(tuA, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + "GDCC_TEST_EXPORT int gdextension_entry(void) { return 1; }\n");
        var host = TargetPlatform.getNativePlatform();
        var outputTypesPerRound = new java.util.ArrayList<List<Integer>>();
        var compiler = newCompilerWithSpy(outputTypesPerRound);

        // tcc has no optimization pipeline, so the level never enters the cache key — the
        // RELEASE round consumes the DEBUG round's entries (the caller encodes the level into
        // the output base name, keeping artifacts distinct).
        var debug = compiler.compile(tempDir, List.of(), List.of(tuA), "probe_debug", COptimizationLevel.DEBUG, host);
        assertTrue(debug.success(), () -> "debug round failed:\n" + debug.buildLog());
        var release = compiler.compile(tempDir, List.of(), List.of(tuA), "probe_release", COptimizationLevel.RELEASE, host);

        assertTrue(release.success(), () -> "release round failed:\n" + release.buildLog());
        assertEquals("[gdcc] tcc object cache: 1 hits, 0 misses\n", release.buildLog(),
                "the RELEASE round reuses the DEBUG round's entries");
        NativeArtifactInspector.assertExportsSymbol(release.artifacts().getFirst(), "gdextension_entry");
    }

    /// dlopens the artifact and calls `gdextension_entry()` — proves the linked library actually
    /// runs the current round's code, which symbol presence alone cannot show.
    private static int callEntry(@NotNull Path library) throws Throwable {
        var linker = java.lang.foreign.Linker.nativeLinker();
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var lookup = java.lang.foreign.SymbolLookup.libraryLookup(library, arena);
            var symbol = lookup.find("gdextension_entry");
            assertTrue(symbol.isPresent(), "gdextension_entry not found in " + library);
            var handle = linker.downcallHandle(symbol.get(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));
            return (int) handle.invokeExact();
        }
    }

    /// Wraps the real FFM bridge with a spy recording each state's output type, so tests can
    /// pin how many OBJ/DLL states a round created without faking the native behavior.
    private static @NotNull TinyCcCompiler newCompilerWithSpy(@NotNull List<List<Integer>> outputTypesPerRound) {
        return new TinyCcCompiler(() -> bundleRoot, (root, targetPlatform, roundArena) -> {
            var delegate = TinyCcFfmNative.create(root, targetPlatform, roundArena);
            var stateOutputTypes = new java.util.ArrayList<Integer>();
            outputTypesPerRound.add(stateOutputTypes);
            return new SpyTinyCcNative(delegate, stateOutputTypes);
        });
    }

    private static final class SpyTinyCcNative implements TinyCcNative {
        private final TinyCcNative delegate;
        private final List<Integer> stateOutputTypes;

        private SpyTinyCcNative(@NotNull TinyCcNative delegate, @NotNull List<Integer> stateOutputTypes) {
            this.delegate = delegate;
            this.stateOutputTypes = stateOutputTypes;
        }

        @Override
        public @Nullable java.lang.foreign.MemorySegment newState() {
            return delegate.newState();
        }

        @Override
        public void delete(@NotNull java.lang.foreign.MemorySegment state) {
            delegate.delete(state);
        }

        @Override
        public void setLibPath(@NotNull java.lang.foreign.MemorySegment state, @NotNull String path) {
            delegate.setLibPath(state, path);
        }

        @Override
        public int setOptions(@NotNull java.lang.foreign.MemorySegment state, @NotNull String options) {
            return delegate.setOptions(state, options);
        }

        @Override
        public int setOutputType(@NotNull java.lang.foreign.MemorySegment state, int outputType) {
            stateOutputTypes.add(outputType);
            return delegate.setOutputType(state, outputType);
        }

        @Override
        public int addIncludePath(@NotNull java.lang.foreign.MemorySegment state, @NotNull String path) {
            return delegate.addIncludePath(state, path);
        }

        @Override
        public int addFile(@NotNull java.lang.foreign.MemorySegment state, @NotNull String file) {
            return delegate.addFile(state, file);
        }

        @Override
        public int outputFile(@NotNull java.lang.foreign.MemorySegment state, @NotNull String file) {
            return delegate.outputFile(state, file);
        }

        @Override
        public void setErrorHandler(@NotNull java.lang.foreign.MemorySegment state, @NotNull ErrorHandler handler) {
            delegate.setErrorHandler(state, handler);
        }

        @Override
        public @Nullable Throwable callbackFailure() {
            return delegate.callbackFailure();
        }
    }
}
