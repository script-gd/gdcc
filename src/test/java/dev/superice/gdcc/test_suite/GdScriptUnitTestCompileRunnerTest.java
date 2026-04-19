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
            "runtime/string_literal_internal_surface.gd",
            "smoke/basic_arithmetic.gd",
            "subscript/array_roundtrip.gd",
            "subscript/packed_array_mutation_roundtrip.gd"
    );

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
    Stream<DynamicTest> compilesAndValidatesBundledUnitScripts() throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "Zig not found; skipping GDScript unit compile runner test");

        var runner = new GdScriptUnitTestCompileRunner();
        var scriptPaths = runner.listScriptResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(EXPECTED_SCRIPT_PATHS, scriptPaths, () -> "Unexpected bundled unit-test script set: " + scriptPaths);

        return scriptPaths.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> new GdScriptUnitTestCompileRunner().compileAndValidate(scriptResourcePath)
                ));
    }
}
