package dev.superice.gdcc.test_suite;

import dev.superice.gdcc.backend.c.build.GodotGdextensionTestRunner;
import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptEngineVirtualOverrideRuntimeTest {
    private static final List<String> SCRIPT_RESOURCE_PATHS = List.of(
            "runtime/virtual/physics_process_called_and_delta_valid.gd",
            "runtime/virtual/process_called_and_delta_valid.gd",
            "runtime/virtual/ready_called_once.gd"
    );
    private static final Path SCRIPT_ROOT = Path.of("src", "test", "test_suite", "unit_test", "script");
    private static final Path VALIDATION_ROOT = Path.of("src", "test", "test_suite", "unit_test", "validation");

    @Test
    void virtualRuntimeFixturesRemainBundledAndPaired() throws Exception {
        var discoveredScriptPaths = new GdScriptUnitTestCompileRunner().listScriptResourcePaths();
        for (var scriptResourcePath : SCRIPT_RESOURCE_PATHS) {
            assertTrue(
                    discoveredScriptPaths.contains(scriptResourcePath),
                    () -> "Bundled unit-test script set did not include " + scriptResourcePath
            );
            assertTrue(
                    Files.exists(SCRIPT_ROOT.resolve(scriptResourcePath)),
                    () -> "Source fixture missing on disk: " + scriptResourcePath
            );
            assertTrue(
                    Files.exists(VALIDATION_ROOT.resolve(scriptResourcePath)),
                    () -> "Validation fixture missing on disk: " + scriptResourcePath
            );
        }
    }

    @Test
    void virtualRuntimeFixturesRelyOnEngineDrivenProcessingOnly() throws IOException {
        // Keep the fixture contract explicit so future edits do not silently reintroduce manual toggles.
        for (var scriptResourcePath : SCRIPT_RESOURCE_PATHS) {
            assertFixtureDoesNotContain(SCRIPT_ROOT.resolve(scriptResourcePath), "set_process(");
            assertFixtureDoesNotContain(SCRIPT_ROOT.resolve(scriptResourcePath), "set_physics_process(");
            assertFixtureDoesNotContain(VALIDATION_ROOT.resolve(scriptResourcePath), "set_process(");
            assertFixtureDoesNotContain(VALIDATION_ROOT.resolve(scriptResourcePath), "set_physics_process(");
            assertFixtureDoesNotContain(VALIDATION_ROOT.resolve(scriptResourcePath), "quit_after_frames=");
        }

        assertFixtureContains(
                VALIDATION_ROOT.resolve("runtime/virtual/process_called_and_delta_valid.gd"),
                "process_frame"
        );
        assertFixtureContains(
                VALIDATION_ROOT.resolve("runtime/virtual/physics_process_called_and_delta_valid.gd"),
                "physics_frame"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesVirtualRuntimeScripts() {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "Zig not found; skipping engine virtual compile-run tests");
        Assumptions.assumeTrue(
                GodotGdextensionTestRunner.findGodotBinaryFromEnv() != null,
                "GODOT_BIN not found; skipping engine virtual compile-run tests"
        );

        return SCRIPT_RESOURCE_PATHS.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> new GdScriptUnitTestCompileRunner().compileAndValidate(scriptResourcePath)
                ));
    }

    private static void assertFixtureContains(Path path, String requiredFragment) throws IOException {
        var content = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(
                content.contains(requiredFragment),
                () -> path + " did not contain required fragment `" + requiredFragment + "`.\nContent:\n" + content
        );
    }

    private static void assertFixtureDoesNotContain(Path path, String forbiddenFragment) throws IOException {
        var content = Files.readString(path, StandardCharsets.UTF_8);
        assertFalse(
                content.contains(forbiddenFragment),
                () -> path + " unexpectedly contained forbidden fragment `" + forbiddenFragment + "`.\nContent:\n" + content
        );
    }
}
