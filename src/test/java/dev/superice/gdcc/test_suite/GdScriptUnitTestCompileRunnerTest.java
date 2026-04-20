package dev.superice.gdcc.test_suite;

import dev.superice.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class GdScriptUnitTestCompileRunnerTest {
    private static final List<String> EXPECTED_SCRIPT_PATHS = List.of(
            "abi/array/plain_surface_roundtrip.gd",
            "abi/typed_array/array_leaf_return_roundtrip.gd",
            "abi/typed_array/builtin_method_wrong_typed_guard.gd",
            "abi/typed_array/method_exact_roundtrip.gd",
            "abi/typed_array/method_plain_guard.gd",
            "abi/typed_array/method_wrong_typed_guard.gd",
            "abi/typed_array/property_plain_guard.gd",
            "abi/typed_array/property_roundtrip.gd",
            "abi/typed_array/property_wrong_typed_guard.gd",
            "abi/typed_array/return_roundtrip.gd",
            "abi/typed_dictionary/method_exact_roundtrip.gd",
            "abi/typed_dictionary/method_plain_guard.gd",
            "abi/typed_dictionary/method_wrong_typed_guard.gd",
            "abi/typed_dictionary/property_plain_guard.gd",
            "abi/typed_dictionary/property_roundtrip.gd",
            "abi/typed_dictionary/property_wrong_typed_guard.gd",
            "abi/typed_dictionary/return_roundtrip.gd",
            "abi/variant/method_roundtrip.gd",
            "abi/variant/non_variant_guard.gd",
            "abi/variant/property_roundtrip.gd",
            "algorithm/fibonacci_sequence.gd",
            "algorithm/graph_traversal.gd",
            "collection/array_sum_and_mutation.gd",
            "collection/dictionary_mutation_and_lookup.gd",
            "constructor/builtin_variant_container_roundtrip.gd",
            "constructor/builtin_variant_scalar_roundtrip.gd",
            "control_flow/if_elif_truthiness.gd",
            "control_flow/recursive_factorial.gd",
            "initializer/local/arithmetic_chain.gd",
            "initializer/local/constructors_and_constants.gd",
            "initializer/local/object_and_engine_constructor.gd",
            "initializer/local/variant_boundaries.gd",
            "initializer/property/object_and_scalar.gd",
            "member/builtin_property_access.gd",
            "member/builtin_property_writeback_color.gd",
            "member/builtin_property_writeback_vector3.gd",
            "member/compound_assignment.gd",
            "runtime/array_constructor_size.gd",
            "runtime/array_void_return_helper_size.gd",
            "runtime/array_void_return_push_back_size.gd",
            "runtime/comment_statement_control_flow_surface.gd",
            "runtime/dynamic_call.gd",
            "runtime/engine_array_mesh_exact_default_args.gd",
            "runtime/engine_node_add_child_exact_explicit_internal_args.gd",
            "runtime/engine_node_add_child_exact_typed_receiver.gd",
            "runtime/engine_node_call_exact_vararg_discard_return.gd",
            "runtime/engine_node_call_exact_vararg_error_path.gd",
            "runtime/engine_node_call_exact_vararg_success.gd",
            "runtime/engine_node_refcounted_workflow.gd",
            "runtime/engine_option_button_default_args.gd",
            "runtime/engine_scene_tree_call_group_flags_exact_vararg.gd",
            "runtime/string_literal_escape_unicode_surface.gd",
            "runtime/string_literal_internal_surface.gd",
            "runtime/string_literal_utf8_offset_surface.gd",
            "smoke/basic_arithmetic.gd",
            "subscript/array_roundtrip.gd",
            "subscript/packed_array_mutation_roundtrip.gd"
    );
    private static final List<String> ABI_SCRIPT_PATHS = scriptPathsWithPrefix("abi/");
    private static final List<String> ALGORITHM_SCRIPT_PATHS = scriptPathsWithPrefix("algorithm/");
    private static final List<String> COLLECTION_SCRIPT_PATHS = scriptPathsWithPrefix("collection/");
    private static final List<String> CONSTRUCTOR_SCRIPT_PATHS = scriptPathsWithPrefix("constructor/");
    private static final List<String> CONTROL_FLOW_SCRIPT_PATHS = scriptPathsWithPrefix("control_flow/");
    private static final List<String> INITIALIZER_SCRIPT_PATHS = scriptPathsWithPrefix("initializer/");
    private static final List<String> MEMBER_SCRIPT_PATHS = scriptPathsWithPrefix("member/");
    private static final List<String> RUNTIME_SCRIPT_PATHS = scriptPathsWithPrefix("runtime/");
    private static final List<String> SMOKE_SCRIPT_PATHS = scriptPathsWithPrefix("smoke/");
    private static final List<String> SUBSCRIPT_SCRIPT_PATHS = scriptPathsWithPrefix("subscript/");

    @Test
    void listsExpectedBundledUnitScripts() throws Exception {
        var runner = new GdScriptUnitTestCompileRunner();
        var scriptPaths = runner.listScriptResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(
                EXPECTED_SCRIPT_PATHS,
                scriptPaths,
                () -> "Unexpected bundled unit-test script set: " + scriptPaths
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesAbiScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                ABI_SCRIPT_PATHS,
                "Zig not found; skipping ABI GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesAlgorithmScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                ALGORITHM_SCRIPT_PATHS,
                "Zig not found; skipping algorithm GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesCollectionScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                COLLECTION_SCRIPT_PATHS,
                "Zig not found; skipping collection GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesConstructorScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                CONSTRUCTOR_SCRIPT_PATHS,
                "Zig not found; skipping constructor GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesControlFlowScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                CONTROL_FLOW_SCRIPT_PATHS,
                "Zig not found; skipping control-flow GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesInitializerScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                INITIALIZER_SCRIPT_PATHS,
                "Zig not found; skipping initializer GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesMemberScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                MEMBER_SCRIPT_PATHS,
                "Zig not found; skipping member GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesRuntimeScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                RUNTIME_SCRIPT_PATHS,
                "Zig not found; skipping runtime GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesSmokeScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                SMOKE_SCRIPT_PATHS,
                "Zig not found; skipping smoke GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesSubscriptScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                SUBSCRIPT_SCRIPT_PATHS,
                "Zig not found; skipping subscript GDScript compile-run tests"
        );
    }

    private static Stream<DynamicTest> compileAndValidateBundledUnitScripts(
            List<String> scriptPaths,
            String skipMessage
    ) throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, skipMessage);

        var runner = new GdScriptUnitTestCompileRunner();
        var discoveredScriptPaths = runner.listScriptResourcePaths();
        assertFalse(discoveredScriptPaths.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(
                EXPECTED_SCRIPT_PATHS,
                discoveredScriptPaths,
                () -> "Unexpected bundled unit-test script set: " + discoveredScriptPaths
        );

        return scriptPaths.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> new GdScriptUnitTestCompileRunner().compileAndValidate(scriptResourcePath)
                ));
    }

    private static List<String> scriptPathsWithPrefix(String prefix) {
        return EXPECTED_SCRIPT_PATHS.stream()
                .filter(scriptPath -> scriptPath.startsWith(prefix))
                .toList();
    }
}
