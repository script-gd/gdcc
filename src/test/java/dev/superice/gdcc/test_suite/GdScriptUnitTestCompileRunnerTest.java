package dev.superice.gdcc.test_suite;

import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class GdScriptUnitTestCompileRunnerTest {
    private static final List<String> EXPECTED_SCRIPT_PATHS = List.of(
            "control_flow/if_elif_truthiness.gd",
            "control_flow/recursive_factorial.gd",
            "initializer/local/arithmetic_chain.gd",
            "initializer/local/constructors_and_constants.gd",
            "initializer/local/object_and_engine_constructor.gd",
            "initializer/local/variant_boundaries.gd",
            "initializer/property/object_and_scalar.gd",
            "member/builtin_property_access.gd",
            "member/compound_assignment.gd",
            "runtime/dynamic_call.gd",
            "smoke/basic_arithmetic.gd",
            "subscript/array_roundtrip.gd"
    );

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesBundledUnitScripts() throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "Zig not found; skipping GDScript unit compile runner test");

        var runner = new GdScriptUnitTestCompileRunner();
        var scriptPaths = runner.listScriptResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(
                EXPECTED_SCRIPT_PATHS,
                scriptPaths,
                () -> "Unexpected bundled unit-test script set: " + scriptPaths
        );

        return scriptPaths.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> new GdScriptUnitTestCompileRunner().compileAndValidate(scriptResourcePath)
                ));
    }
}
