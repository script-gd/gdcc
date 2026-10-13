package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the backend-selection contract of [CProjectBuilder]:
/// - the dispatching shape routes every build round to the compiler registered for
///   [CProjectInfo.getCCompilerKind] and never touches the other backend;
/// - the forced (single-compiler) shape ignores the requested kind, preserving the semantics
///   existing single-compiler callers were written against;
/// - the dispatching constructor is fail-closed: every [CCompilerKind] must have a registered
///   compiler so no round can dispatch to a missing entry;
/// - [CProjectBuilder.availableCCompilerKinds] is a pure function of the environment probes and
///   the production wiring reports exactly what the real probes see.
class CProjectBuilderCompilerSelectionTest {
    @Test
    void dispatchesEachRoundToTheCompilerNamedByTheProjectInfo(@TempDir Path tempDir) throws IOException {
        var zig = new RecordingCompiler();
        var tcc = new RecordingCompiler();
        var builder = new CProjectBuilder(
                Map.of(CCompilerKind.ZIG_CC, zig, CCompilerKind.TINY_CC, tcc),
                Map.of()
        );

        build(builder, tempDir.resolve("first"), CCompilerKind.TINY_CC);
        assertEquals(0, zig.invocations.get());
        assertEquals(1, tcc.invocations.get());

        build(builder, tempDir.resolve("second"), CCompilerKind.ZIG_CC);
        assertEquals(1, zig.invocations.get());
        assertEquals(1, tcc.invocations.get());

        build(builder, tempDir.resolve("third"), CCompilerKind.TINY_CC);
        assertEquals(1, zig.invocations.get());
        assertEquals(2, tcc.invocations.get());
    }

    @Test
    void forcedCompilerIgnoresTheRequestedKind(@TempDir Path tempDir) throws IOException {
        var forced = new RecordingCompiler();
        var builder = new CProjectBuilder(forced);

        build(builder, tempDir.resolve("project"), CCompilerKind.TINY_CC);

        assertEquals(1, forced.invocations.get());
    }

    @Test
    void dispatchingConstructorRequiresACompilerForEveryKind() {
        var compiler = new RecordingCompiler();

        // Map.of rejects null values outright; the missing-kind case is the fail-closed anchor.
        var exception = assertThrows(NullPointerException.class, () ->
                new CProjectBuilder(Map.of(CCompilerKind.ZIG_CC, compiler), Map.of()));
        assertEquals(
                "compilers must contain an entry for " + CCompilerKind.TINY_CC,
                exception.getMessage()
        );
        assertThrows(NullPointerException.class, () -> new CProjectBuilder(Map.of(), Map.of()));
    }

    @Test
    void availableCCompilerKindsFollowTheInjectedProbes() {
        assertEquals(
                List.of(CCompilerKind.ZIG_CC),
                CProjectBuilder.availableCCompilerKinds(Map.of(
                        CCompilerKind.ZIG_CC, () -> true,
                        CCompilerKind.TINY_CC, () -> false
                ))
        );
        assertEquals(
                List.of(),
                CProjectBuilder.availableCCompilerKinds(Map.of(
                        CCompilerKind.ZIG_CC, () -> false,
                        CCompilerKind.TINY_CC, () -> false
                ))
        );
        // Declaration order is the reporting order, independent of the probe map's iteration.
        assertEquals(
                List.of(CCompilerKind.ZIG_CC, CCompilerKind.TINY_CC),
                CProjectBuilder.availableCCompilerKinds(Map.of(
                        CCompilerKind.TINY_CC, () -> true,
                        CCompilerKind.ZIG_CC, () -> true
                ))
        );
        // A kind without a probe counts as unavailable rather than crashing the query.
        assertEquals(
                List.of(CCompilerKind.TINY_CC),
                CProjectBuilder.availableCCompilerKinds(Map.of(CCompilerKind.TINY_CC, () -> true))
        );
    }

    @Test
    void productionAvailabilityReportMatchesTheRealEnvironmentProbes() {
        // Not gated: whatever the host provides, the public report must equal what the two
        // production probes independently observe (zig discovery / bundle presence).
        var expectedZig = ZigUtil.isZigDiscoverable();
        var expectedTcc = TinyCcBundle.isHostBundlePresent();

        var reported = CProjectBuilder.availableCCompilerKinds();

        assertEquals(expectedZig, reported.contains(CCompilerKind.ZIG_CC));
        assertEquals(expectedTcc, reported.contains(CCompilerKind.TINY_CC));
        assertEquals(List.of(CCompilerKind.values()).stream().filter(reported::contains).toList(), reported);
        // The no-spawn peek must agree with the spawning discovery: `which` only searches PATH
        // (scanned directly), and `where`'s extra current-directory rule on Windows is mirrored
        // by the peek (pinned in ZigUtilTest).
        assertEquals(ZigUtil.findZig() != null, ZigUtil.isZigDiscoverable());
    }

    @Test
    void availabilityQueryNeverTouchesTheCallerInterruptStatus() throws InterruptedException {
        // The query is a read-only picker surface: neither setting nor clearing the flag is
        // acceptable — setting would fake a cancellation, clearing would swallow a real one.
        for (var initiallyInterrupted : List.of(false, true)) {
            var observed = new AtomicBoolean();
            var thread = new Thread(() -> {
                if (initiallyInterrupted) {
                    Thread.currentThread().interrupt();
                }
                CProjectBuilder.availableCCompilerKinds();
                observed.set(Thread.currentThread().isInterrupted());
            });
            thread.start();
            thread.join();
            assertEquals(initiallyInterrupted, observed.get());
        }
    }

    private static void build(
            @NotNull CProjectBuilder builder,
            @NotNull Path projectDir,
            @NotNull CCompilerKind kind
    ) throws IOException {
        var projectInfo = new CProjectInfo(
                "testproj", GodotVersion.V451, projectDir,
                COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform(), kind);
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), new LirModule(projectInfo.projectName(), List.of()));
        var result = builder.buildProject(projectInfo, codegen);
        assertTrue(result.success(), () -> "build must succeed with the recording compiler: " + result.buildLog());
    }

    private static final class RecordingCompiler implements CCompiler {
        private final @NotNull AtomicInteger invocations = new AtomicInteger();

        @Override
        public CCompileResult compile(
                @NotNull Path projectDir,
                @NotNull List<Path> includeDirs,
                @NotNull List<Path> cFiles,
                @NotNull String outputBaseName,
                @NotNull COptimizationLevel optimizationLevel,
                @NotNull TargetPlatform targetPlatform
        ) throws IOException {
            invocations.incrementAndGet();
            var artifact = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName));
            Files.writeString(artifact, "dummy");
            return new CCompileResult(true, "ok", List.of(artifact));
        }
    }
}
