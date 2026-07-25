package gd.script.gdcc.test_suite.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gd.script.gdcc.backend.c.build.CCompileResult;
import gd.script.gdcc.backend.c.build.CCompiler;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptBenchmarkRunnerTest {
    private static final List<String> BASELINE_BENCHMARK_SCRIPT_PATHS = List.of(
            "algorithm/int_loop.gd",
            "collection/array_mutation.gd",
            "collection/quadtree_lookup.gd",
            "math/newton_sqrt.gd",
            "runtime/stringname_roundtrip.gd"
    );

    @Test
    void listsBundledBenchmarkScriptsWithBaselineCases() throws Exception {
        var runner = new GdScriptBenchmarkRunner();
        var scriptPaths = runner.listBenchmarkResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled benchmark case");
        assertEquals(scriptPaths.stream().sorted().toList(), scriptPaths);
        assertTrue(
                scriptPaths.containsAll(BASELINE_BENCHMARK_SCRIPT_PATHS),
                () -> "Bundled benchmark scripts should keep baseline coverage while allowing new cases: " + scriptPaths
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
    void rejectsUnexpectedMeasurementFixtureWithoutCompiledCounterpart(@TempDir Path tempDir) throws Exception {
        writeTextResource(tempDir, "benchmark/script/algorithm/int_loop.gd", "class_name BenchmarkCompiled\nextends Node\n");
        writeTextResource(tempDir, "benchmark/interpreter/algorithm/int_loop.gd", "class_name BenchmarkInterpreter\nextends Node\n");
        writeTextResource(tempDir, "benchmark/measurement/algorithm/int_loop.gd", "extends Node\n");
        writeTextResource(tempDir, "benchmark/measurement/algorithm/extra_case.gd", "extends Node\n");

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null)) {
            var runner = new GdScriptBenchmarkRunner(loader);
            var error = assertThrows(AssertionError.class, runner::listBenchmarkResourcePaths);
            assertTrue(
                    error.getMessage().contains("algorithm/extra_case.gd"),
                    () -> "Unexpected measurement counterpart should report exact relative path, got: " + error.getMessage()
            );
            assertTrue(
                    error.getMessage().contains("benchmark/measurement"),
                    () -> "Unexpected measurement counterpart should report measurement root, got: " + error.getMessage()
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
        assertEquals("Integer loop", result.config().name());
        assertEquals(50_000, result.config().iterations());
        assertEquals(3, result.config().warmups());
        assertEquals(10, result.config().samples());
        assertEquals(1_000, result.config().minBatchUs());
        var metadataFile = result.projectDir().resolve("entry.h");
        assertTrue(
                Files.exists(metadataFile),
                () -> "Expected current runtime layout generated file `entry.h` to exist under " + result.projectDir()
        );
        assertEquals(COptimizationLevel.RELEASE, result.projectSetup().optimizationLevel());
        assertEquals(2, result.projectSetup().scriptResources().size());
    }

    @Test
    void compileBenchmarkCaseShouldFailOnFrontendDiagnostics(@TempDir Path tempDir) throws Exception {
        writeBenchmarkFixture(
                tempDir,
                "algorithm/frontend_error.gd",
                """
                        extends Node
                        """
        );
        writeTextResource(
                tempDir,
                "benchmark/script/algorithm/frontend_error.gd",
                """
                        class_name FrontendErrorCompiled
                        extends Node

                        func benchmark() -> int:
                            var total: int = "invalid"
                            return total
                        """
        );
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            var runner = new GdScriptBenchmarkRunner(loader, new RecordingCompiler(new CCompileResult(true, "ok", List.of(artifact))));
            var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/frontend_error.gd"));
            assertTrue(error.getMessage().contains("diagnostics"), error::getMessage);
            assertTrue(error.getMessage().contains("frontend_error.gd"), error::getMessage);
        }
    }

    @Test
    void compileBenchmarkCaseShouldPrepareDualTargetProjectSetup(@TempDir Path tempDir) throws Exception {
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);
        var compiler = new RecordingCompiler(new CCompileResult(true, "release build ok", List.of(artifact)));
        var runner = new GdScriptBenchmarkRunner(getClass().getClassLoader(), compiler);

        var result = runner.compileBenchmarkCase("algorithm/int_loop.gd");

        var projectSetup = result.projectSetup();
        assertEquals(3, projectSetup.sceneNodes().size());
        assertEquals(GdScriptBenchmarkRunner.COMPILED_TARGET_NODE_NAME, projectSetup.sceneNodes().getFirst().nodeName());
        assertEquals(result.runtimeClassName(), projectSetup.sceneNodes().getFirst().nodeType());
        var interpreterNode = projectSetup.sceneNodes().get(1);
        assertEquals(GdScriptBenchmarkRunner.INTERPRETER_TARGET_NODE_NAME, interpreterNode.nodeName());
        assertEquals("res://benchmark/interpreter/algorithm/int_loop.gd", interpreterNode.scriptResourcePath());
        var measurementNode = projectSetup.sceneNodes().getLast();
        assertEquals(GdScriptBenchmarkRunner.MEASUREMENT_NODE_NAME, measurementNode.nodeName());
        assertEquals("res://benchmark/measurement/algorithm/int_loop.gd", measurementNode.scriptResourcePath());

        var interpreterScript = projectSetup.scriptResources().stream()
                .filter(resource -> resource.resourcePath().contains("/interpreter/"))
                .findFirst()
                .orElseThrow();
        assertFalse(interpreterScript.scriptContent().contains("# gdcc-benchmark:"));
        assertTrue(interpreterScript.scriptContent().contains("class_name BenchmarkIntLoopInterpreter"));

        var measurementScript = projectSetup.scriptResources().stream()
                .filter(resource -> resource.resourcePath().contains("/measurement/"))
                .findFirst()
                .orElseThrow();
        assertFalse(measurementScript.scriptContent().contains("# gdcc-benchmark:"));
        assertTrue(measurementScript.scriptContent().contains(GdScriptBenchmarkRunner.COMPILED_TARGET_NODE_NAME));
        assertTrue(measurementScript.scriptContent().contains(GdScriptBenchmarkRunner.INTERPRETER_TARGET_NODE_NAME));
        assertTrue(measurementScript.scriptContent().contains("res://benchmark/interpreter/algorithm/int_loop.gd"));
        assertTrue(measurementScript.scriptContent().contains("GDCC_BENCHMARK_HEADER case=%s name=%s iterations=%d warmups=%d samples=%d min_batch_us=%d"));
        assertTrue(measurementScript.scriptContent().contains("const ITERATIONS = 50000"));
        assertTrue(measurementScript.scriptContent().contains("const WARMUPS = 3"));
        assertTrue(measurementScript.scriptContent().contains("const SAMPLES = 10"));
        assertTrue(measurementScript.scriptContent().contains("const MIN_BATCH_US = 1000"));
        assertTrue(measurementScript.scriptContent().contains(GdScriptBenchmarkRunner.expectedPassMarker("algorithm/int_loop.gd")));
    }

    @Test
    void parseBenchmarkConfigShouldAcceptKnownDirectiveValues() {
        var config = GdScriptBenchmarkRunner.parseBenchmarkConfig(
                "algorithm/int_loop.gd",
                """
                        # gdcc-benchmark: name=Custom Name
                        # gdcc-benchmark: iterations=64
                        # gdcc-benchmark: warmups=2
                        # gdcc-benchmark: samples=5
                        # gdcc-benchmark: min_batch_us=9
                        # gdcc-benchmark: output_contains=compiled
                        # gdcc-benchmark: output_not_contains=error
                        extends Node
                        """
        );

        assertEquals("Custom Name", config.name());
        assertEquals(64, config.iterations());
        assertEquals(2, config.warmups());
        assertEquals(5, config.samples());
        assertEquals(9, config.minBatchUs());
        assertEquals(List.of("compiled"), config.outputExpectations().outputContains());
        assertEquals(List.of("error"), config.outputExpectations().outputNotContains());
    }

    @Test
    void parseCaseOutputShouldRejectMissingExpectedOutputText() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of("must-appear"), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("must-appear"));
    }

    @Test
    void compileBenchmarkCaseShouldRejectUnknownBenchmarkDirective(@TempDir Path tempDir) throws Exception {
        writeTextResource(
                tempDir,
                "benchmark/script/algorithm/invalid_directive.gd",
                """
                        class_name InvalidDirectiveCompiled
                        extends Node
                        """
        );
        writeTextResource(
                tempDir,
                "benchmark/interpreter/algorithm/invalid_directive.gd",
                """
                        class_name InvalidDirectiveInterpreter
                        extends Node
                        """
        );
        writeTextResource(
                tempDir,
                "benchmark/measurement/algorithm/invalid_directive.gd",
                """
                        # gdcc-benchmark: unsupported=value
                        extends Node
                        """
        );
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            var runner = new GdScriptBenchmarkRunner(loader, new RecordingCompiler(new CCompileResult(true, "ok", List.of(artifact))));
            var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/invalid_directive.gd"));
            assertTrue(error.getMessage().contains("unsupported=value"));
            assertTrue(error.getMessage().contains("invalid_directive.gd"));
        }
    }

    @Test
    void compileBenchmarkCaseShouldRejectExecutableMeasurementDescriptorBody(@TempDir Path tempDir) throws Exception {
        writeBenchmarkFixture(
                tempDir,
                "algorithm/executable_measurement.gd",
                """
                        # gdcc-benchmark: name=Executable measurement
                        extends Node
                        """
        );
        writeTextResource(
                tempDir,
                GdScriptBenchmarkRunner.MEASUREMENT_TEMPLATE_RESOURCE,
                """
                        extends Node
                        """
        );
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            var runner = new GdScriptBenchmarkRunner(loader, new RecordingCompiler(new CCompileResult(true, "ok", List.of(artifact))));
            var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/executable_measurement.gd"));
            assertTrue(error.getMessage().contains("must contain only"));
            assertTrue(error.getMessage().contains(GdScriptBenchmarkRunner.MEASUREMENT_TEMPLATE_RESOURCE));
            assertTrue(error.getMessage().contains("algorithm/executable_measurement.gd"));
        }
    }

    @Test
    void compileBenchmarkCaseShouldRejectBlankBenchmarkDirectiveValue(@TempDir Path tempDir) throws Exception {
        writeTextResource(
                tempDir,
                "benchmark/script/algorithm/blank_directive.gd",
                """
                        class_name BlankDirectiveCompiled
                        extends Node
                        """
        );
        writeTextResource(
                tempDir,
                "benchmark/interpreter/algorithm/blank_directive.gd",
                """
                        class_name BlankDirectiveInterpreter
                        extends Node
                        """
        );
        writeTextResource(
                tempDir,
                "benchmark/measurement/algorithm/blank_directive.gd",
                """
                        # gdcc-benchmark: iterations=
                        extends Node
                        """
        );
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            var runner = new GdScriptBenchmarkRunner(loader, new RecordingCompiler(new CCompileResult(true, "ok", List.of(artifact))));
            var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/blank_directive.gd"));
            assertTrue(error.getMessage().contains("iterations="));
            assertTrue(error.getMessage().contains("non-empty value"));
        }
    }

    @Test
    void compileBenchmarkCaseShouldRejectNonNumericDirectiveValue(@TempDir Path tempDir) throws Exception {
        writeBenchmarkFixture(
                tempDir,
                "algorithm/non_numeric.gd",
                """
                        # gdcc-benchmark: iterations=nope
                        extends Node
                        """
        );
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            var runner = new GdScriptBenchmarkRunner(loader, new RecordingCompiler(new CCompileResult(true, "ok", List.of(artifact))));
            var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/non_numeric.gd"));
            assertTrue(error.getMessage().contains("iterations"));
            assertTrue(error.getMessage().contains("nope"));
        }
    }

    @Test
    void compileBenchmarkCaseShouldRejectNonPositiveDirectiveValue(@TempDir Path tempDir) throws Exception {
        writeBenchmarkFixture(
                tempDir,
                "algorithm/non_positive.gd",
                """
                        # gdcc-benchmark: samples=0
                        extends Node
                        """
        );
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            var runner = new GdScriptBenchmarkRunner(loader, new RecordingCompiler(new CCompileResult(true, "ok", List.of(artifact))));
            var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/non_positive.gd"));
            assertTrue(error.getMessage().contains("samples"));
            assertTrue(error.getMessage().contains("> 0"));
        }
    }

    @Test
    void buildFailureIncludesNativeBuildLog() {
        var compiler = new RecordingCompiler(new CCompileResult(false, "native build exploded", List.of()));
        var runner = new GdScriptBenchmarkRunner(getClass().getClassLoader(), compiler);

        var error = assertThrows(AssertionError.class, () -> runner.compileBenchmarkCase("algorithm/int_loop.gd"));
        assertTrue(error.getMessage().contains("native build exploded"));
        assertTrue(error.getMessage().contains("algorithm/int_loop.gd"));
    }

    @Test
    void parseCaseOutputShouldComputeStatisticsWarningsAndReportShape() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                2,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of("GDCC_BENCHMARK_RESULT"), List.of("panic"))
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=2 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=1 iterations=1000 baseline_us=80 benchmark_us=20 body_ns=-60 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=false check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=1 iterations=1000 baseline_us=60 benchmark_us=1160 body_ns=1100 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(
                0,
                true,
                false,
                false,
                output,
                "",
                List.of("godot", "--headless")
        );

        var report = GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output);

        assertEquals(1, report.schemaVersion());
        assertEquals(1, report.cases().size());
        var summary = report.cases().getFirst();
        assertEquals("algorithm/int_loop.gd", summary.casePath());
        assertEquals("Integer loop", summary.name());
        assertEquals("passed", summary.status());
        assertTrue(summary.passMarkerSeen());
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.startsWith(GdScriptBenchmarkRunner.WARNING_NEGATIVE_BODY)));
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.startsWith(GdScriptBenchmarkRunner.WARNING_SHORT_BATCH)));
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.startsWith(GdScriptBenchmarkRunner.WARNING_MISSING_CHECK)));
        var compiled = Objects.requireNonNull(summary.compiled());
        var interpreter = Objects.requireNonNull(summary.interpreter());
        var ratio = Objects.requireNonNull(summary.ratio());
        assertEquals(2, compiled.samples());
        assertEquals(30.0, compiled.meanBodyNs());
        assertEquals(127.27922061357856, compiled.stddevBodyNs());
        assertEquals(-60, compiled.minBodyNs());
        assertEquals(120, compiled.maxBodyNs());
        assertEquals(60.0, compiled.meanOverheadNs());
        assertEquals(2, interpreter.rawSamples().size());
        assertEquals(1050.0, interpreter.meanBodyNs());
        assertEquals(70.71067811865476, interpreter.stddevBodyNs());
        assertEquals(0.02857142857142857, ratio.compiledToInterpreterMean());
        assertEquals("[gdcc-benchmark] case=algorithm/int_loop.gd compiled.mean=0.030us compiled.stddev=0.127us interpreter.mean=1.050us interpreter.stddev=0.071us ratio=0.0286 samples=2 iterations=1000",
                GdScriptBenchmarkRunner.summaryLine(summary));
    }

    @Test
    void parseCaseOutputShouldKeepPerPathPerSampleOverheadSubtraction() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                4,
                0,
                2,
                1,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=4 warmups=0 samples=2 min_batch_us=1
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=4 baseline_us=8 benchmark_us=20 body_ns=3000 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=1 iterations=4 baseline_us=12 benchmark_us=28 body_ns=4000 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=4 baseline_us=4 benchmark_us=44 body_ns=10000 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=1 iterations=4 baseline_us=16 benchmark_us=56 body_ns=10000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var summary = GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output).cases().getFirst();
        var compiled = Objects.requireNonNull(summary.compiled());
        var interpreter = Objects.requireNonNull(summary.interpreter());

        assertEquals(3_000, compiled.rawSamples().getFirst().bodyNs());
        assertEquals(4_000, compiled.rawSamples().getLast().bodyNs());
        assertEquals(3_500.0, compiled.meanBodyNs());
        assertEquals(2_500.0, compiled.meanOverheadNs());
        assertEquals(10_000.0, interpreter.meanBodyNs());
        assertEquals(2_500.0, interpreter.meanOverheadNs());
    }

    @Test
    void parseBenchmarkConfigShouldCaptureRaisedIterationsFromBundledShortBenchmarks(@TempDir Path tempDir) throws Exception {
        var artifact = tempDir.resolve("compiled_release_x86_64.so");
        Files.writeString(artifact, "binary", StandardCharsets.UTF_8);
        var compiler = new RecordingCompiler(new CCompileResult(true, "release build ok", List.of(artifact)));
        var runner = new GdScriptBenchmarkRunner(getClass().getClassLoader(), compiler);

        assertEquals(50_000, runner.compileBenchmarkCase("collection/array_mutation.gd").config().iterations());
        assertEquals(10_000, runner.compileBenchmarkCase("math/vector3_transform.gd").config().iterations());
        assertEquals(20_000, runner.compileBenchmarkCase("runtime/stringname_roundtrip.gd").config().iterations());
    }

    @Test
    void runtimeProjectDirectoryShouldBePerCaseAndUnderBenchmarkWorkRoot() {
        var runtimeRoot = Path.of("tmp/test/test_suite/benchmark/runtime");
        var algorithmDir = GdScriptBenchmarkRunner.runtimeProjectDirForCase("algorithm/int_loop.gd");
        var collectionDir = GdScriptBenchmarkRunner.runtimeProjectDirForCase("collection/array_mutation.gd");

        assertTrue(algorithmDir.startsWith(runtimeRoot));
        assertTrue(collectionDir.startsWith(runtimeRoot));
        assertNotEquals(algorithmDir, collectionDir);
        assertTrue(algorithmDir.toString().endsWith("algorithm_int_loop"));
        assertTrue(collectionDir.toString().endsWith("collection_array_mutation"));
    }

    @Test
    void reportPathShouldBeMergedAndUnderBenchmarkWorkRoot() {
        assertEquals(Path.of("tmp/test/test_suite/benchmark/report.json"), GdScriptBenchmarkRunner.reportPath());
    }

    @Test
    void parseCaseOutputShouldRejectMalformedNumericField() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=nope benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("baseline_us"));
        assertTrue(error.getMessage().contains("nope"));
    }

    @Test
    void parseCaseOutputShouldRejectDuplicateHeader() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("Duplicate benchmark header"));
    }

    @Test
    void parseCaseOutputShouldRejectUnknownRuntimePath() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=native sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("Unknown benchmark path"));
    }

    @Test
    void parseCaseOutputShouldRejectCaseMismatch() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/other.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("Unexpected header case"));
    }

    @Test
    void assertStopSignalSeenShouldReportTimeoutWithCombinedOutput() {
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(
                -1,
                false,
                true,
                true,
                "stdout before timeout",
                "stderr before timeout",
                List.of("godot", "--headless")
        );

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.assertStopSignalSeen("algorithm/int_loop.gd", runResult));
        assertTrue(error.getMessage().contains("Test stop."));
        assertTrue(error.getMessage().contains("Timed out: true"));
        assertTrue(error.getMessage().contains("stdout before timeout"));
        assertTrue(error.getMessage().contains("stderr before timeout"));
        assertTrue(error.getMessage().contains("godot"));
    }

    @Test
    void parseCaseOutputShouldRejectMissingPassMarker() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("pass marker"));
    }

    @Test
    void parseCaseOutputShouldRejectInconsistentSampleCount() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                2,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=2 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=1 iterations=1000 baseline_us=60 benchmark_us=1160 body_ns=1100 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("Expected 2 benchmark samples"));
    }

    @Test
    void parseCaseOutputShouldRejectFailedBehaviorCheck() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=false
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var error = assertThrows(AssertionError.class, () -> GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output));
        assertTrue(error.getMessage().contains("behavior check failed"));
    }

    @Test
    void failedReportShouldPreserveDiagnosticsWithoutStatistics() {
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(
                1,
                false,
                false,
                false,
                "GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000",
                "stderr boom",
                List.of("godot", "--headless")
        );
        var report = GdScriptBenchmarkRunner.failedReport(
                "algorithm/int_loop.gd",
                new GdScriptBenchmarkRunner.BenchmarkConfig(
                        "Integer loop",
                        1000,
                        3,
                        1,
                        1000,
                        new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
                ),
                runResult,
                runResult.combinedOutput(),
                new AssertionError("Missing benchmark pass marker for algorithm/int_loop.gd")
        );

        var caseSummary = report.cases().getFirst();
        assertEquals("failed", caseSummary.status());
        assertTrue(Objects.requireNonNull(caseSummary.failure()).contains("Missing benchmark pass marker"));
        assertFalse(caseSummary.passMarkerSeen());
        assertNull(caseSummary.compiled());
        assertNull(caseSummary.interpreter());
        assertNull(caseSummary.ratio());
        assertEquals(runResult.combinedOutput(), caseSummary.combinedOutput());
    }

    @Test
    void renderReportJsonShouldUseNumericDurationFieldsAndForwardSlashPaths() {
        var report = new BenchmarkReport(
                1,
                "2026-06-14T00:00:00Z",
                new BenchmarkReport.EnvironmentSummary("Linux", "x86_64", "25", "/tmp/godot", "4.5.1", "/tmp/zig", "LINUX_X86_64", "RELEASE"),
                List.of(new BenchmarkReport.CaseSummary(
                        "algorithm\\int_loop.gd",
                        "Integer loop",
                        new BenchmarkReport.ReportConfig(3, 2, 1000, 1000),
                        "passed",
                        List.of(),
                        null,
                        new BenchmarkReport.PathStatistics(
                                2,
                                120.0,
                                8.5,
                                108,
                                134,
                                35.0,
                                List.of(new BenchmarkReport.RawSample(0, 1000, 35, 155, 120)),
                                List.of()
                        ),
                        new BenchmarkReport.PathStatistics(2, 950.0, 50.0, 880, 1010, 42.0, List.of(), List.of()),
                        new BenchmarkReport.RatioSummary(0.1263, 7.9167),
                        true,
                        List.of("godot", "--headless"),
                        "output"
                ))
        );

        var json = BenchmarkReportWriter.renderReportJson(report);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, root.get("schema_version").getAsInt());
        assertEquals("RELEASE", root.getAsJsonObject("environment").get("optimization").getAsString());
        JsonArray cases = root.getAsJsonArray("cases");
        assertEquals(1, cases.size());
        JsonObject caseObject = cases.get(0).getAsJsonObject();
        assertEquals("algorithm/int_loop.gd", caseObject.get("case").getAsString());
        assertFalse(caseObject.has("failure"));
        assertTrue(caseObject.get("compiled").getAsJsonObject().get("mean_body_ns").isJsonPrimitive());
        assertFalse(json.contains("1.23ms"));
    }

    @Test
    void renderReportJsonShouldRetainWarningsRawSamplesAndCombinedOutput() {
        var report = new BenchmarkReport(
                1,
                "2026-06-15T00:00:00Z",
                new BenchmarkReport.EnvironmentSummary("Linux", "x86_64", "25", "/tmp/godot", "4.5.1", "/tmp/zig", "LINUX_X86_64", "RELEASE"),
                List.of(new BenchmarkReport.CaseSummary(
                        "runtime/stringname_roundtrip.gd",
                        "StringName roundtrip",
                        new BenchmarkReport.ReportConfig(3, 2, 1000, 1000),
                        "passed",
                        List.of(GdScriptBenchmarkRunner.WARNING_NEGATIVE_BODY + ":compiled:1"),
                        null,
                        new BenchmarkReport.PathStatistics(
                                2,
                                12.0,
                                3.0,
                                9,
                                15,
                                4.0,
                                List.of(
                                        new BenchmarkReport.RawSample(0, 1000, 4, 16, 12),
                                        new BenchmarkReport.RawSample(1, 1000, 5, 14, 9)
                                ),
                                List.of(GdScriptBenchmarkRunner.WARNING_SHORT_BATCH + ":compiled:0")
                        ),
                        new BenchmarkReport.PathStatistics(
                                2,
                                24.0,
                                1.0,
                                23,
                                25,
                                6.0,
                                List.of(new BenchmarkReport.RawSample(0, 1000, 6, 30, 24)),
                                List.of(GdScriptBenchmarkRunner.WARNING_MISSING_CHECK + ":interpreter:0")
                        ),
                        new BenchmarkReport.RatioSummary(0.5, 2.0),
                        true,
                        List.of("godot", "--headless"),
                        "GDCC_BENCHMARK_RESULT case=runtime/stringname_roundtrip.gd"
                ))
        );

        var root = JsonParser.parseString(BenchmarkReportWriter.renderReportJson(report)).getAsJsonObject();
        var caseObject = root.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals(
                "GDCC_BENCHMARK_RESULT case=runtime/stringname_roundtrip.gd",
                caseObject.get("combined_output").getAsString()
        );
        assertEquals(
                GdScriptBenchmarkRunner.WARNING_NEGATIVE_BODY + ":compiled:1",
                caseObject.getAsJsonArray("warnings").get(0).getAsString()
        );
        var compiled = caseObject.getAsJsonObject("compiled");
        assertEquals(2, compiled.getAsJsonArray("raw_samples").size());
        assertEquals(
                GdScriptBenchmarkRunner.WARNING_SHORT_BATCH + ":compiled:0",
                compiled.getAsJsonArray("warnings").get(0).getAsString()
        );
        assertEquals(16, compiled.getAsJsonArray("raw_samples").get(0).getAsJsonObject().get("benchmark_us").getAsInt());
        assertEquals(0.5, caseObject.getAsJsonObject("ratio").get("compiled_to_interpreter_mean").getAsDouble());
    }

    @Test
    void renderMinimalReportJsonShouldDropHeavyRuntimeFields() {
        var report = new BenchmarkReport(
                1,
                "2026-06-15T00:00:00Z",
                new BenchmarkReport.EnvironmentSummary("Linux", "x86_64", "25", "/tmp/godot", "4.5.1", "/tmp/zig", "LINUX_X86_64", "RELEASE"),
                List.of(new BenchmarkReport.CaseSummary(
                        "runtime/stringname_roundtrip.gd",
                        "StringName roundtrip",
                        new BenchmarkReport.ReportConfig(3, 2, 1000, 1000),
                        "passed",
                        List.of(GdScriptBenchmarkRunner.WARNING_NEGATIVE_BODY + ":compiled:1"),
                        null,
                        new BenchmarkReport.PathStatistics(
                                2,
                                12.0,
                                3.0,
                                9,
                                15,
                                4.0,
                                List.of(
                                        new BenchmarkReport.RawSample(0, 1000, 4, 16, 12),
                                        new BenchmarkReport.RawSample(1, 1000, 5, 14, 9)
                                ),
                                List.of(GdScriptBenchmarkRunner.WARNING_SHORT_BATCH + ":compiled:0")
                        ),
                        new BenchmarkReport.PathStatistics(
                                2,
                                24.0,
                                1.0,
                                23,
                                25,
                                6.0,
                                List.of(new BenchmarkReport.RawSample(0, 1000, 6, 30, 24)),
                                List.of(GdScriptBenchmarkRunner.WARNING_MISSING_CHECK + ":interpreter:0")
                        ),
                        new BenchmarkReport.RatioSummary(0.5, 2.0),
                        true,
                        List.of("godot", "--headless"),
                        "GDCC_BENCHMARK_RESULT case=runtime/stringname_roundtrip.gd"
                ))
        );

        var root = JsonParser.parseString(BenchmarkReportWriter.renderMinimalReportJson(report)).getAsJsonObject();
        var caseObject = root.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertFalse(caseObject.has("pass_marker_seen"));
        assertFalse(caseObject.has("command"));
        assertFalse(caseObject.has("combined_output"));
        assertFalse(caseObject.getAsJsonObject("compiled").has("raw_samples"));
        assertFalse(caseObject.getAsJsonObject("interpreter").has("raw_samples"));
        assertEquals("passed", caseObject.get("status").getAsString());
        assertEquals(0.5, caseObject.getAsJsonObject("ratio").get("compiled_to_interpreter_mean").getAsDouble());
    }

    @Test
    void appendReportCaseShouldMergeCasesIntoSingleReport(@TempDir Path tempDir) throws Exception {
        var reportPath = tempDir.resolve("report.json");
        var firstReport = new BenchmarkReport(
                1,
                "2026-06-15T00:00:00Z",
                new BenchmarkReport.EnvironmentSummary("Linux", "x86_64", "25", "/tmp/godot", "4.5.1", "/tmp/zig", "LINUX_X86_64", "RELEASE"),
                List.of(passedCaseSummary("algorithm/int_loop.gd", "Integer loop", 50_000))
        );
        var secondReport = new BenchmarkReport(
                1,
                "2026-06-15T00:01:00Z",
                new BenchmarkReport.EnvironmentSummary("Linux", "x86_64", "25", "/tmp/godot", "4.5.1", "/tmp/zig", "LINUX_X86_64", "RELEASE"),
                List.of(passedCaseSummary("math/newton_sqrt.gd", "Newton sqrt", 10_000))
        );

        GdScriptBenchmarkRunner.appendReportCase(reportPath, firstReport);
        var merged = GdScriptBenchmarkRunner.appendReportCase(reportPath, secondReport);

        assertEquals(List.of("algorithm/int_loop.gd", "math/newton_sqrt.gd"), merged.cases().stream().map(BenchmarkReport.CaseSummary::casePath).toList());
        var root = JsonParser.parseString(Files.readString(reportPath)).getAsJsonObject();
        var cases = root.getAsJsonArray("cases");
        assertEquals(2, cases.size());
        assertEquals("algorithm/int_loop.gd", cases.get(0).getAsJsonObject().get("case").getAsString());
        assertEquals("math/newton_sqrt.gd", cases.get(1).getAsJsonObject().get("case").getAsString());
        assertEquals(10_000, cases.get(1).getAsJsonObject().getAsJsonObject("config").get("iterations").getAsInt());
        var minRoot = JsonParser.parseString(Files.readString(tempDir.resolve("report-min.json"))).getAsJsonObject();
        var minCase = minRoot.getAsJsonArray("cases").get(1).getAsJsonObject();
        assertFalse(minCase.has("pass_marker_seen"));
        assertFalse(minCase.has("command"));
        assertFalse(minCase.has("combined_output"));
    }

    @Test
    void summaryLineShouldRenderInfiniteRatioWhenInterpreterMeanIsZero() {
        var summary = new BenchmarkReport.CaseSummary(
                "runtime/stringname_roundtrip.gd",
                "StringName roundtrip",
                new BenchmarkReport.ReportConfig(3, 1, 20_000, 1_000),
                "passed",
                List.of(),
                null,
                new BenchmarkReport.PathStatistics(1, 250.0, 0.0, 250, 250, 10.0, List.of(), List.of()),
                new BenchmarkReport.PathStatistics(1, 0.0, 0.0, 0, 0, 8.0, List.of(), List.of()),
                new BenchmarkReport.RatioSummary(Double.POSITIVE_INFINITY, 0.0),
                true,
                List.of("godot", "--headless"),
                "output"
        );
        assertEquals(
                "[gdcc-benchmark] case=runtime/stringname_roundtrip.gd compiled.mean=0.250us compiled.stddev=0.000us interpreter.mean=0.000us interpreter.stddev=0.000us ratio=inf samples=1 iterations=20000",
                GdScriptBenchmarkRunner.summaryLine(summary)
        );
    }

    private static BenchmarkReport.CaseSummary passedCaseSummary(@NotNull String casePath, @NotNull String name, int iterations) {
        return new BenchmarkReport.CaseSummary(
                casePath,
                name,
                new BenchmarkReport.ReportConfig(3, 1, iterations, 1000),
                "passed",
                List.of(),
                null,
                new BenchmarkReport.PathStatistics(1, 30.0, 0.0, 30, 30, 4.0, List.of(new BenchmarkReport.RawSample(0, iterations, 4, 34, 30)), List.of()),
                new BenchmarkReport.PathStatistics(1, 45.0, 0.0, 45, 45, 5.0, List.of(new BenchmarkReport.RawSample(0, iterations, 5, 50, 45)), List.of()),
                new BenchmarkReport.RatioSummary(0.6667, 1.5),
                true,
                List.of("godot", "--headless"),
                "GDCC_BENCHMARK_RESULT"
        );
    }

    @Test
    void writeFailedReportShouldPersistFailureJson(@TempDir Path tempDir) throws Exception {
        var output = "Godot stdout\nGodot stderr";
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(
                1,
                false,
                false,
                false,
                "Godot stdout",
                "Godot stderr",
                List.of("godot", "--headless")
        );
        var reportPath = tempDir.resolve("report.json");

        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        BenchmarkReportWriter.writeReport(reportPath, GdScriptBenchmarkRunner.failedReport("algorithm/int_loop.gd", config, runResult, output, new AssertionError("Malformed benchmark field `baseline_us`")));

        var caseObject = JsonParser.parseString(Files.readString(reportPath))
                .getAsJsonObject()
                .getAsJsonArray("cases")
                .get(0)
                .getAsJsonObject();
        assertEquals("failed", caseObject.get("status").getAsString());
        assertEquals("Malformed benchmark field `baseline_us`", caseObject.get("failure").getAsString());
        assertFalse(caseObject.has("compiled"));
        assertFalse(caseObject.has("interpreter"));
        assertFalse(caseObject.has("ratio"));
        assertEquals(output, caseObject.get("combined_output").getAsString());
    }

    @Test
    void writeReportShouldCreateParentDirectoriesAndPersistJson(@TempDir Path tempDir) throws Exception {
        var report = new BenchmarkReport(
                1,
                "2026-06-14T00:00:00Z",
                new BenchmarkReport.EnvironmentSummary("Linux", "x86_64", "25", "/tmp/godot", "4.5.1", "/tmp/zig", "LINUX_X86_64", "RELEASE"),
                List.of(new BenchmarkReport.CaseSummary(
                        "collection/array_mutation.gd",
                        "Array mutation",
                        new BenchmarkReport.ReportConfig(3, 1, 1000, 1000),
                        "passed",
                        List.of(),
                        null,
                        new BenchmarkReport.PathStatistics(1, 30.0, 0.0, 30, 30, 4.0, List.of(new BenchmarkReport.RawSample(0, 1000, 4, 34, 30)), List.of()),
                        new BenchmarkReport.PathStatistics(1, 45.0, 0.0, 45, 45, 5.0, List.of(new BenchmarkReport.RawSample(0, 1000, 5, 50, 45)), List.of()),
                        new BenchmarkReport.RatioSummary(0.6667, 1.5),
                        true,
                        List.of("godot", "--headless"),
                        "GDCC_BENCHMARK_RESULT"
                ))
        );
        var reportPath = tempDir.resolve("nested/reports/report.json");

        BenchmarkReportWriter.writeReport(reportPath, report);

        assertTrue(Files.exists(reportPath));
        var root = JsonParser.parseString(Files.readString(reportPath)).getAsJsonObject();
        assertEquals(1, root.get("schema_version").getAsInt());
        assertEquals("collection/array_mutation.gd", root.getAsJsonArray("cases").get(0).getAsJsonObject().get("case").getAsString());
    }

    @Test
    void parseCaseOutputShouldCaptureReleaseEnvironmentForReport() {
        var config = new GdScriptBenchmarkRunner.BenchmarkConfig(
                "Integer loop",
                1000,
                3,
                1,
                1000,
                new GdScriptBenchmarkRunner.OutputExpectations(List.of(), List.of())
        );
        var output = """
                GDCC_BENCHMARK_HEADER case=algorithm/int_loop.gd name=Integer+loop iterations=1000 warmups=3 samples=1 min_batch_us=1000
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=compiled sample=0 iterations=1000 baseline_us=40 benchmark_us=160 body_ns=120 check_ran=true check_passed=true
                GDCC_BENCHMARK_RESULT case=algorithm/int_loop.gd path=interpreter sample=0 iterations=1000 baseline_us=50 benchmark_us=1050 body_ns=1000 check_ran=true check_passed=true
                GDCC_BENCHMARK_PASS::algorithm/int_loop.gd
                Test stop.
                """;
        var runResult = new GodotGdextensionTestRunner.GodotRunResult(0, true, false, false, output, "", List.of("godot"));

        var environment = GdScriptBenchmarkRunner.parseCaseOutput("algorithm/int_loop.gd", config, runResult, output).environment();

        assertEquals(System.getProperty("os.name"), environment.os());
        assertEquals(System.getProperty("os.arch"), environment.arch());
        assertEquals(System.getProperty("java.version"), environment.javaVersion());
        assertEquals(TargetPlatform.getNativePlatform().name(), environment.targetPlatform());
        assertEquals(COptimizationLevel.RELEASE.name(), environment.optimization());
    }

    private static void writeBenchmarkFixture(@NotNull Path root, @NotNull String relativePath, @NotNull String measurementScript) throws IOException {
        writeTextResource(root, "benchmark/script/" + relativePath, "class_name FixtureCompiled\nextends Node\nfunc baseline() -> int:\n    return 1\nfunc benchmark() -> int:\n    return 2\n");
        writeTextResource(root, "benchmark/interpreter/" + relativePath, "class_name FixtureInterpreter\nextends Node\nfunc baseline() -> int:\n    return 1\nfunc benchmark() -> int:\n    return 2\n");
        writeTextResource(root, "benchmark/measurement/" + relativePath, measurementScript);
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
