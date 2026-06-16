package gd.script.gdcc.test_suite.benchmark;

import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptBenchmarkRuntimeTest {
    private static final String RUN_BENCHMARKS_ENV = "GDCC_RUN_BENCHMARKS";

    @TestFactory
    Stream<DynamicTest> compilesBundledBenchmarkScriptsToReleaseArtifacts() throws Exception {
        Assumptions.assumeTrue(
                ZigUtil.findZig() != null,
                "Zig not found; skipping bundled benchmark release-build tests"
        );
        var runner = new GdScriptBenchmarkRunner();
        var scriptPaths = runner.listBenchmarkResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled benchmark case");

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

    @TestFactory
    Stream<DynamicTest> compilesRunsAndReportsAlgorithmBenchmarks() throws Exception {
        return runBundledBenchmarkScripts(
                "algorithm/",
                "Zig not found; skipping algorithm benchmark runtime tests",
                "GODOT_BIN not found; skipping algorithm benchmark runtime tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesRunsAndReportsCollectionBenchmarks() throws Exception {
        return runBundledBenchmarkScripts(
                "collection/",
                "Zig not found; skipping collection benchmark runtime tests",
                "GODOT_BIN not found; skipping collection benchmark runtime tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesRunsAndReportsMathBenchmarks() throws Exception {
        return runBundledBenchmarkScripts(
                "math/",
                "Zig not found; skipping math benchmark runtime tests",
                "GODOT_BIN not found; skipping math benchmark runtime tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesRunsAndReportsRuntimeBenchmarks() throws Exception {
        return runBundledBenchmarkScripts(
                "runtime/",
                "Zig not found; skipping runtime benchmark runtime tests",
                "GODOT_BIN not found; skipping runtime benchmark runtime tests"
        );
    }

    private static Stream<DynamicTest> runBundledBenchmarkScripts(
            String prefix,
            String zigSkipMessage,
            String godotSkipMessage
    ) throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, zigSkipMessage);
        Assumptions.assumeTrue(
                GodotGdextensionTestRunner.findGodotBinaryFromEnv() != null,
                godotSkipMessage
        );
        Assumptions.assumeTrue(
                benchmarkRuntimeEnabled(),
                () -> "Set " + RUN_BENCHMARKS_ENV + "=1 to enable Godot-backed benchmark runtime tests"
        );

        var runner = new GdScriptBenchmarkRunner();
        var discoveredScriptPaths = runner.listBenchmarkResourcePaths();
        assertFalse(discoveredScriptPaths.isEmpty(), "Expected at least one bundled benchmark case");
        var filteredScriptPaths = discoveredScriptPaths.stream()
                .filter(scriptPath -> scriptPath.startsWith(prefix))
                .toList();
        assertFalse(filteredScriptPaths.isEmpty(), () -> "No bundled benchmark cases found for prefix `" + prefix + "`.");
        assertEquals(filteredScriptPaths.stream().sorted().toList(), filteredScriptPaths);

        return filteredScriptPaths.stream().map(scriptPath -> DynamicTest.dynamicTest(
                scriptPath,
                () -> {
                    var result = new GdScriptBenchmarkRunner().compileAndRunBenchmarkCase(scriptPath);
                    var expectedReportPath = GdScriptBenchmarkRunner.reportPathForCase(scriptPath);
                    assertTrue(result.runResult().stopSignalSeen());
                    assertTrue(result.runResult().combinedOutput().contains(GdScriptBenchmarkRunner.RESULT_LINE_PREFIX));
                    assertTrue(result.runResult().combinedOutput().contains(GdScriptBenchmarkRunner.expectedPassMarker(scriptPath)));
                    assertTrue(Files.exists(result.reportPath()));
                    assertEquals(expectedReportPath, result.reportPath());
                    assertEquals(scriptPath, result.report().cases().getFirst().casePath());
                }
        ));
    }


    private static boolean benchmarkRuntimeEnabled() {
        return isEnabledEnvValue(System.getenv(RUN_BENCHMARKS_ENV));
    }

    /// Keeps the accepted opt-in values explicit so the runtime gate and the operator guide stay
    /// anchored to the same contract.
    static boolean isEnabledEnvValue(String value) {
        if (value == null) {
            return false;
        }
        return Set.of("1", "true", "yes", "on").contains(value.toLowerCase(Locale.ROOT));
    }
}
