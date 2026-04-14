package dev.superice.gdcc.test_suite;

import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

/// Focused compile/link/run anchors for builtin unary-Variant constructor routes.
/// Keep these cases directly targetable so we do not need to rerun the whole resource-driven suite
/// while iterating on the constructor special-case contract.
public class GdScriptBuiltinConstructorVariantCompileRunnerTest {
    private static final List<String> TARGET_SCRIPT_PATHS = List.of(
            "constructor/builtin_variant_container_roundtrip.gd",
            "constructor/builtin_variant_scalar_roundtrip.gd"
    );

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesBuiltinVariantConstructorCases() {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "Zig not found; skipping builtin Variant constructor compile-run tests");

        return TARGET_SCRIPT_PATHS.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> new GdScriptUnitTestCompileRunner().compileAndValidate(scriptResourcePath)
                ));
    }
}
