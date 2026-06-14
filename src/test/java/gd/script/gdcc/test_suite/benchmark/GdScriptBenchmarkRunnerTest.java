package gd.script.gdcc.test_suite.benchmark;

import gd.script.gdcc.backend.c.build.CCompileResult;
import gd.script.gdcc.backend.c.build.CCompiler;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptBenchmarkRunnerTest {
    private static final List<String> EXPECTED_BENCHMARK_SCRIPT_PATHS = List.of(
            "algorithm/int_loop.gd"
    );

    @Test
    void listsExpectedBundledBenchmarkScripts() throws Exception {
        var runner = new GdScriptBenchmarkRunner();
        var scriptPaths = runner.listBenchmarkResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled benchmark case");
        assertEquals(
                EXPECTED_BENCHMARK_SCRIPT_PATHS,
                scriptPaths,
                () -> "Unexpected bundled benchmark script set: " + scriptPaths
        );
    }

    @Test
    void failsWhenBenchmarkDirectoryIsEmpty(@TempDir Path tempDir) throws Exception {
        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null)) {
            var runner = new GdScriptBenchmarkRunner(loader);
            var error = assertThrows(AssertionError.class, runner::listBenchmarkResourcePaths);
            assertTrue(
                    error.getMessage().contains("No benchmark script resources found under benchmark/script"),
                    () -> "Unexpected empty benchmark fixture message: " + error.getMessage()
            );
        }
    }

    @Test
    void failsWhenInterpreterCounterpartIsMissing(@TempDir Path tempDir) throws Exception {
        writeTextResource(tempDir, "benchmark/script/algorithm/int_loop.gd", "class_name BenchmarkCompiled\nextends Node\n");
        writeTextResource(tempDir, "benchmark/measurement/algorithm/int_loop.gd", "extends Node\n");

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null)) {
            var runner = new GdScriptBenchmarkRunner(loader);
            var error = assertThrows(AssertionError.class, runner::listBenchmarkResourcePaths);
            assertTrue(
                    error.getMessage().contains("algorithm/int_loop.gd"),
                    () -> "Missing interpreter counterpart should report exact relative path, got: " + error.getMessage()
            );
            assertTrue(
                    error.getMessage().contains("benchmark/interpreter"),
                    () -> "Missing interpreter counterpart should report interpreter root, got: " + error.getMessage()
            );
        }
    }

    @Test
    void failsWhenMeasurementCounterpartIsMissing(@TempDir Path tempDir) throws Exception {
        writeTextResource(tempDir, "benchmark/script/algorithm/int_loop.gd", "class_name BenchmarkCompiled\nextends Node\n");
        writeTextResource(tempDir, "benchmark/interpreter/algorithm/int_loop.gd", "class_name BenchmarkInterpreter\nextends Node\n");

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null)) {
            var runner = new GdScriptBenchmarkRunner(loader);
            var error = assertThrows(AssertionError.class, runner::listBenchmarkResourcePaths);
            assertTrue(
                    error.getMessage().contains("algorithm/int_loop.gd"),
                    () -> "Missing measurement counterpart should report exact relative path, got: " + error.getMessage()
            );
            assertTrue(
                    error.getMessage().contains("benchmark/measurement"),
                    () -> "Missing measurement counterpart should report measurement root, got: " + error.getMessage()
            );
        }
    }

    @Test
    void rejectsUnexpectedInterpreterFixtureWithoutCompiledCounterpart(@TempDir Path tempDir) throws Exception {
        writeTextResource(tempDir, "benchmark/script/algorithm/int_loop.gd", "class_name BenchmarkCompiled\nextends Node\n");
        writeTextResource(tempDir, "benchmark/interpreter/algorithm/int_loop.gd", "class_name BenchmarkInterpreter\nextends Node\n");
        writeTextResource(tempDir, "benchmark/interpreter/algorithm/extra_case.gd", "class_name ExtraInterpreter\nextends Node\n");
        writeTextResource(tempDir, "benchmark/measurement/algorithm/int_loop.gd", "extends Node\n");

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null)) {
            var runner = new GdScriptBenchmarkRunner(loader);
            var error = assertThrows(AssertionError.class, runner::listBenchmarkResourcePaths);
            assertTrue(
                    error.getMessage().contains("algorithm/extra_case.gd"),
                    () -> "Unexpected interpreter counterpart should report exact relative path, got: " + error.getMessage()
            );
        }
    }

    @Test
    void benchmarkResourceOrderingStaysStableAcrossDuplicateClasspathRoots(@TempDir Path tempDir) throws Exception {
        var rootA = tempDir.resolve("root-a");
        var rootB = tempDir.resolve("root-b");
        writeTextResource(rootA, "benchmark/script/zeta/case_b.gd", "class_name ZetaCompiled\nextends Node\n");
        writeTextResource(rootA, "benchmark/script/alpha/case_a.gd", "class_name AlphaCompiled\nextends Node\n");
        writeTextResource(rootA, "benchmark/interpreter/zeta/case_b.gd", "class_name ZetaInterpreter\nextends Node\n");
        writeTextResource(rootA, "benchmark/interpreter/alpha/case_a.gd", "class_name AlphaInterpreter\nextends Node\n");
        writeTextResource(rootA, "benchmark/measurement/zeta/case_b.gd", "extends Node\n");
        writeTextResource(rootA, "benchmark/measurement/alpha/case_a.gd", "extends Node\n");

        writeTextResource(rootB, "benchmark/script/alpha/case_a.gd", "class_name AlphaCompiledDuplicate\nextends Node\n");
        writeTextResource(rootB, "benchmark/interpreter/alpha/case_a.gd", "class_name AlphaInterpreterDuplicate\nextends Node\n");
        writeTextResource(rootB, "benchmark/measurement/alpha/case_a.gd", "extends Node\n");

        try (var loader = new URLClassLoader(new URL[]{rootB.toUri().toURL(), rootA.toUri().toURL()}, null)) {
            var runner = new GdScriptBenchmarkRunner(loader);
            assertEquals(
                    List.of("alpha/case_a.gd", "zeta/case_b.gd"),
                    runner.listBenchmarkResourcePaths()
            );
        }
    }

    @Test
    void releaseBuildUsesReleaseOptimizationAndPreservesBuildLog(@TempDir Path tempDir) throws Exception {
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);
        var compiler = new RecordingCompiler(new CCompileResult(true, "release build ok", List.of(artifact)));
        var runner = new GdScriptBenchmarkRunner(getClass().getClassLoader(), compiler);

        var result = runner.compileBenchmarkCase("algorithm/int_loop.gd");

        assertEquals(COptimizationLevel.RELEASE, compiler.lastOptimizationLevel());
        assertSame(TargetPlatform.getNativePlatform(), compiler.lastTargetPlatform());
        assertEquals("release build ok", result.buildResult().buildLog());
        assertTrue(result.buildResult().success());
        assertEquals(result.requireDynamicLibraryArtifact(), artifact);
        var metadataFile = result.projectDir().resolve("entry.h");
        assertTrue(
                Files.exists(metadataFile),
                () -> "Expected current runtime layout generated file `entry.h` to exist under " + result.projectDir()
        );
    }

    @Test
    void buildFailureIncludesNativeBuildLog() {
        var compiler = new RecordingCompiler(new CCompileResult(false, "native build exploded", List.of()));
        var runner = new GdScriptBenchmarkRunner(getClass().getClassLoader(), compiler);

        var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/int_loop.gd"));
        assertTrue(error.getMessage().contains("native build exploded"));
        assertTrue(error.getMessage().contains("algorithm/int_loop.gd"));
    }

    @TestFactory
    Stream<DynamicTest> compilesBundledBenchmarkScriptsToReleaseArtifacts() throws Exception {
        Assumptions.assumeTrue(
                ZigUtil.findZig() != null,
                "Zig not found; skipping bundled benchmark release-build tests"
        );
        var runner = new GdScriptBenchmarkRunner();
        var scriptPaths = runner.listBenchmarkResourcePaths();
        assertEquals(EXPECTED_BENCHMARK_SCRIPT_PATHS, scriptPaths);

        return scriptPaths.stream().map(scriptPath -> DynamicTest.dynamicTest(
                scriptPath,
                () -> {
                    var result = new GdScriptBenchmarkRunner().compileBenchmarkCase(scriptPath);
                    assertTrue(result.buildResult().success(), () -> "Expected successful release build for " + scriptPath);
                    assertTrue(
                            result.requireDynamicLibraryArtifact().getFileName().toString().contains("_release_"),
                            () -> "Benchmark artifact should encode release optimization in filename: "
                                    + result.requireDynamicLibraryArtifact().getFileName()
                    );
                }
        ));
    }

    private static void writeTextResource(@NotNull Path root, @NotNull String relativePath, @NotNull String content) throws IOException {
        var file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static final class RecordingCompiler implements CCompiler {
        private final @NotNull CCompileResult compileResult;
        private @Nullable COptimizationLevel lastOptimizationLevel;
        private @Nullable TargetPlatform lastTargetPlatform;

        private RecordingCompiler(@NotNull CCompileResult compileResult) {
            this.compileResult = compileResult;
        }

        @Override
        public CCompileResult compile(
                @NotNull Path projectDir,
                @NotNull List<Path> includeDirs,
                @NotNull List<Path> cFiles,
                @NotNull String outputBaseName,
                @NotNull COptimizationLevel optimizationLevel,
                @NotNull TargetPlatform targetPlatform
        ) {
            lastOptimizationLevel = optimizationLevel;
            lastTargetPlatform = targetPlatform;
            return compileResult;
        }

        private @Nullable COptimizationLevel lastOptimizationLevel() {
            return lastOptimizationLevel;
        }

        private @Nullable TargetPlatform lastTargetPlatform() {
            return lastTargetPlatform;
        }
    }
}
