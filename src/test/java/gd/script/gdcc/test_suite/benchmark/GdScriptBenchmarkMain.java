package gd.script.gdcc.test_suite.benchmark;

import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.ZigUtil;

import java.util.List;
import java.util.Objects;

public final class GdScriptBenchmarkMain {
    private GdScriptBenchmarkMain() {
    }

    static void main(String... args) throws Exception {
        Objects.requireNonNull(args);
        requireZig();
        requireGodotBinary();
        var runner = new GdScriptBenchmarkRunner();
        runner.resetReport();
        var scriptPaths = runner.listBenchmarkResourcePaths();
        if (scriptPaths.isEmpty()) {
            throw new IllegalStateException("Expected at least one bundled benchmark case");
        }

        runBenchmarkCases(runner, scriptPaths);
        System.out.println("[gdcc-benchmark] report=" + GdScriptBenchmarkRunner.reportPath().toAbsolutePath());
    }

    private static void runBenchmarkCases(GdScriptBenchmarkRunner runner, List<String> scriptPaths) throws Exception {
        for (var scriptPath : scriptPaths) {
            var result = runner.compileAndRunBenchmarkCase(scriptPath);
            if (!result.runResult().stopSignalSeen()) {
                throw new IllegalStateException("Benchmark run did not reach shutdown for " + scriptPath);
            }
            if (!result.runResult().combinedOutput().contains(GdScriptBenchmarkRunner.RESULT_LINE_PREFIX)) {
                throw new IllegalStateException("Benchmark output missed result lines for " + scriptPath);
            }
            if (!result.runResult().combinedOutput().contains(GdScriptBenchmarkRunner.expectedPassMarker(scriptPath))) {
                throw new IllegalStateException("Benchmark output missed pass marker for " + scriptPath);
            }
            if (result.report().cases().stream().noneMatch(caseSummary -> caseSummary.casePath().equals(scriptPath))) {
                throw new IllegalStateException("Merged report missed case " + scriptPath);
            }
        }
    }

    private static void requireZig() {
        if (ZigUtil.findZig() == null) {
            throw new IllegalStateException("Zig not found; install zig before running benchmarks.");
        }
    }

    private static void requireGodotBinary() {
        if (GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            throw new IllegalStateException("GODOT_BIN must point to a runnable Godot binary before running benchmarks.");
        }
    }
}
