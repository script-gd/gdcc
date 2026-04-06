package test_suite;

import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class GdScriptUnitTestCompileRunnerTest {
    @Test
    void compilesAndValidatesBundledUnitScripts() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping GDScript unit compile runner test");
            return;
        }

        var results = new GdScriptUnitTestCompileRunner().compileAndValidateAll();
        var scriptPaths = results.stream()
                .map(GdScriptUnitTestCompileRunner.CaseResult::scriptResourcePath)
                .toList();

        assertFalse(results.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(
                List.of(
                        "initializer/local/arithmetic_chain.gd",
                        "initializer/local/constructors_and_constants.gd",
                        "initializer/local/object_and_engine_constructor.gd",
                        "initializer/local/variant_boundaries.gd",
                        "smoke/basic_arithmetic.gd"
                ),
                scriptPaths,
                () -> "Unexpected bundled unit-test script set: " + scriptPaths
        );
    }
}
