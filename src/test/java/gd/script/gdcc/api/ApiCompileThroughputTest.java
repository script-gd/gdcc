package gd.script.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Informational throughput measurements for the common API use cases: one module holding a single
/// script and one project-style module holding several cross-referencing scripts. The native
/// compiler is a recording stub, so the numbers cover the API freeze/parse/analyze/lowering/codegen
/// pipeline only. Results are printed as `[gdcc-api-throughput]` lines for humans; the test asserts
/// correctness of every run but intentionally sets no performance threshold, keeping it stable
/// across machines.
class ApiCompileThroughputTest {
    private static final int WARMUP_ITERATIONS = 20;
    private static final int MEASURED_ITERATIONS = 50;

    @Test
    void reportsSingleScriptCompileThroughput(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("demo", "Single Script Compile Throughput");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("single-compile-project")));
        api.putFile("demo", "/src/player.gd", singleScriptSource());

        measureCompileRuns(api, "demo", "compile/single-script", 1);
    }

    @Test
    void reportsMultiScriptProjectCompileThroughput(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("demo", "Multi Script Compile Throughput");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("multi-compile-project")));
        putProjectScripts(api);

        measureCompileRuns(api, "demo", "compile/multi-script-project", 4);
    }

    @Test
    void reportsSingleScriptAnalyzeThroughput(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("demo", "Single Script Analyze Throughput");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("single-analyze-project")));
        api.putFile("demo", "/src/player.gd", singleScriptSource());

        measureAnalyzeRuns(api, "demo", "analyze/single-script", 1);
    }

    @Test
    void reportsMultiScriptProjectAnalyzeThroughput(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("demo", "Multi Script Analyze Throughput");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("multi-analyze-project")));
        putProjectScripts(api);

        measureAnalyzeRuns(api, "demo", "analyze/multi-script-project", 4);
    }

    private static void measureCompileRuns(API api, String moduleId, String scenario, int scriptsPerRun) {
        for (var warmup = 0; warmup < WARMUP_ITERATIONS; warmup++) {
            assertCompileSucceeded(api, moduleId);
        }
        var startedNanos = System.nanoTime();
        for (var iteration = 0; iteration < MEASURED_ITERATIONS; iteration++) {
            assertCompileSucceeded(api, moduleId);
        }
        report(scenario, scriptsPerRun, System.nanoTime() - startedNanos);
    }

    private static void measureAnalyzeRuns(API api, String moduleId, String scenario, int scriptsPerRun) {
        for (var warmup = 0; warmup < WARMUP_ITERATIONS; warmup++) {
            assertAnalyzeSucceeded(api, moduleId);
        }
        var startedNanos = System.nanoTime();
        for (var iteration = 0; iteration < MEASURED_ITERATIONS; iteration++) {
            assertAnalyzeSucceeded(api, moduleId);
        }
        report(scenario, scriptsPerRun, System.nanoTime() - startedNanos);
    }

    private static void assertCompileSucceeded(API api, String moduleId) {
        var result = ApiCompileTestSupport.awaitResult(api, api.compile(moduleId));
        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    private static void assertAnalyzeSucceeded(API api, String moduleId) {
        var result = api.analyze(moduleId, new AnalyzeOptions(true));
        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    private static void report(String scenario, int scriptsPerRun, long totalNanos) {
        var totalMillis = totalNanos / 1_000_000.0;
        var runsPerSecond = MEASURED_ITERATIONS * 1_000_000_000.0 / totalNanos;
        System.out.printf(
                Locale.ROOT,
                "[gdcc-api-throughput] scenario=%s runs=%d scripts/run=%d total=%.1fms avg=%.2fms/run runs/s=%.2f scripts/s=%.2f%n",
                scenario,
                MEASURED_ITERATIONS,
                scriptsPerRun,
                totalMillis,
                totalMillis / MEASURED_ITERATIONS,
                runsPerSecond,
                runsPerSecond * scriptsPerRun
        );
    }

    /// A small player-style script mixing the constructs typical projects use most: typed state,
    /// `_ready`, a while loop, and cross-method calls.
    private static String singleScriptSource() {
        return """
                class_name ThroughputPlayer
                extends Node
                
                var speed: int = 4
                var _position: int = 0
                
                func _ready() -> void:
                    _position = advance(speed)
                
                func advance(steps: int) -> int:
                    var total := 0
                    var step := 0
                    while step < steps:
                        total = total + step
                        step = step + 1
                    return _position + total
                """;
    }

    /// One project-style module with four cross-referencing scripts: a utility, a worker using it,
    /// a coordinator using the worker, and a node entry script using the coordinator.
    private static void putProjectScripts(API api) {
        api.putFile("demo", "/src/math_util.gd", """
                class_name ThroughputMathUtil
                extends RefCounted
                
                func scale(value: int, factor: int) -> int:
                    return value * factor
                """);
        api.putFile("demo", "/src/worker.gd", """
                class_name ThroughputWorker
                extends RefCounted
                
                func work(amount: int) -> int:
                    var util := ThroughputMathUtil.new()
                    var total := 0
                    var index := 0
                    while index < amount:
                        total = total + util.scale(index, 2)
                        index = index + 1
                    return total
                """);
        api.putFile("demo", "/src/coordinator.gd", """
                class_name ThroughputCoordinator
                extends RefCounted
                
                func run() -> int:
                    var worker := ThroughputWorker.new()
                    return worker.work(8)
                """);
        api.putFile("demo", "/src/main.gd", """
                class_name ThroughputMain
                extends Node
                
                var score: int = 0
                
                func _ready() -> void:
                    var coordinator := ThroughputCoordinator.new()
                    score = coordinator.run()
                """);
    }
}
