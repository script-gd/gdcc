package dev.superice.gdcc.test_suite;

import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

/// Focused compile/link/run anchors for the broader resource-driven engine suite additions.
/// Keep common collection, algorithm, and scene cases targetable without rerunning every bundled
/// script during each iteration.
public class GdScriptExpandedCoverageCompileRunnerTest {
    private static final List<String> TARGET_SCRIPT_PATHS = List.of(
            "algorithm/fibonacci_sequence.gd",
            "algorithm/graph_traversal.gd",
            "collection/array_sum_and_mutation.gd",
            "collection/dictionary_mutation_and_lookup.gd",
            "runtime/engine_node_refcounted_workflow.gd",
            "runtime/string_literal_internal_surface.gd"
    );

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesExpandedCoverageCases() {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "Zig not found; skipping expanded coverage compile-run tests");

        return TARGET_SCRIPT_PATHS.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> new GdScriptUnitTestCompileRunner().compileAndValidate(scriptResourcePath)
                ));
    }
}
