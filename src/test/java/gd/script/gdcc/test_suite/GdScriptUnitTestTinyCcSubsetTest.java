package gd.script.gdcc.test_suite;

import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TinyCcCliTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Runs a curated subset of the bundled GDScript unit suite through the tinycc backend (real
/// FFM pipeline via [TinyCcCliTestSupport#newBundleCompiler]) and validates behavior in a real
/// headless Godot, reusing [GdScriptUnitTestCompileRunner] end to end — the same runner the zig
/// suite uses, so identical pass markers prove behavioral parity with the zig artifacts.
///
/// Subset rationale: `coroutine/` exercises the generated coroutine machinery (await/cancel/
/// signals on the minicoro TLS stack), `string_format/` plus the `runtime/string_literal_*` cases
/// exercise string/Unicode surfaces, the property-binding cases exercise builtin property access
/// and typed-container property round-trips, and `runtime/dynamic_call.gd` exercises the
/// dynamic-call dispatch. pow is covered at C level by `GdccOperatorPowSmokeTest`'s tcc leg
/// (the suite has no pow case).
///
/// Assumption-gated on the tinycc bundle and `GODOT_BIN`; classes run `same_thread`, so sharing
/// the runner's `test_project` directory with the zig suite is race-free.
class GdScriptUnitTestTinyCcSubsetTest {

    /// Unicode/escape string surfaces beyond the `string_format/` prefix group.
    private static final List<String> STRING_LITERAL_CASES = List.of(
            "runtime/string_literal_escape_unicode_surface.gd",
            "runtime/string_literal_internal_surface.gd",
            "runtime/string_literal_utf8_offset_surface.gd"
    );
    /// Builtin property access/writeback plus typed-container property round-trips.
    private static final List<String> PROPERTY_BINDING_CASES = List.of(
            "member/builtin_property_access.gd",
            "member/builtin_property_writeback_color.gd",
            "member/builtin_property_writeback_vector3.gd",
            "abi/typed_array/property_roundtrip.gd",
            "abi/typed_dictionary/property_roundtrip.gd",
            "abi/variant/property_roundtrip.gd"
    );
    private static final List<String> DYNAMIC_CALL_CASES = List.of(
            "runtime/dynamic_call.gd"
    );

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesSubsetWithTinyCc() throws Exception {
        var compiler = TinyCcCliTestSupport.newBundleCompiler();
        Assumptions.assumeTrue(compiler != null,
                "no tinycc bundle available (GDCC_TINYCC_HOME unset, no build/tinycc-bundle); skipping the tcc suite subset");
        Assumptions.assumeTrue(GodotGdextensionTestRunner.findGodotBinaryFromEnv() != null,
                "GODOT_BIN not found; skipping the tcc suite subset");

        var runner = new GdScriptUnitTestCompileRunner();
        var subset = selectSubset(runner.listScriptResourcePaths());
        return subset.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        "tcc:" + scriptResourcePath,
                        () -> runner.compileAndValidate(
                                scriptResourcePath,
                                GodotGdextensionTestRunner.defaultRunOptions(true),
                                compiler
                        )
                ));
    }

    /// Derives the subset from discovery (never a copied list, so suite growth cannot silently
    /// desync), then fail-fast anchors that every named case and both prefix groups resolved.
    private static List<String> selectSubset(List<String> discovered) {
        var subset = discovered.stream()
                .filter(path -> path.startsWith("coroutine/")
                        || path.startsWith("string_format/")
                        || STRING_LITERAL_CASES.contains(path)
                        || PROPERTY_BINDING_CASES.contains(path)
                        || DYNAMIC_CALL_CASES.contains(path))
                .toList();
        assertTrue(subset.stream().anyMatch(path -> path.startsWith("coroutine/")),
                "the coroutine prefix group must resolve to at least one case");
        assertTrue(subset.stream().anyMatch(path -> path.startsWith("string_format/")),
                "the string_format prefix group must resolve to at least one case");
        for (var named : Stream.of(STRING_LITERAL_CASES, PROPERTY_BINDING_CASES, DYNAMIC_CALL_CASES)
                .flatMap(List::stream).toList()) {
            assertTrue(discovered.contains(named),
                    "named subset case missing from the discovered suite: " + named);
        }
        assertFalse(subset.isEmpty());
        return subset;
    }
}
