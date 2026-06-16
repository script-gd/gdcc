package gd.script.gdcc.test_suite.benchmark;

import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptBenchmarkCompileTest {
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
}
