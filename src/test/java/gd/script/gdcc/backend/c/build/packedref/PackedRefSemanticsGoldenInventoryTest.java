package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Godot-independent anchor for the §2 behavior-matrix case inventory.
///
/// The golden file owns case PAYLOADS, but its inventory (which cases exist, in which order)
/// is the contract this suite exists to lock: a probe call and its golden line removed
/// together would silently shrink coverage, and the golden-only dual-run comparison
/// structurally cannot detect that. This check lives outside the dual-run test class on
/// purpose — that class skips entirely when `GODOT_BIN` is missing, while this resource-only
/// assertion runs in every environment.
class PackedRefSemanticsGoldenInventoryTest {

    /// The §2 behavior-matrix inventory (plan §2 rows + the dynamic Variant receiver case +
    /// the Phase F mixed-scenario case), in `PackedRefProbes.run_all` emission order.
    private static final List<String> MATRIX_CASE_NAMES = List.of(
            "LOCAL_ALIAS",                      // §2-1
            "PARAM_VISIBILITY",                 // §2-2
            "SCRIPT_PROPERTY",                  // §2-3
            "TYPED_ARRAY_ELEMENT",              // §2-5
            "DICT_VALUE",                       // §2-6
            "BUILTIN_PROPERTY_MUTATION",        // §2-7a
            "BUILTIN_PROPERTY_REASSIGN",        // §2-7b
            "BUILTIN_PROPERTY_SUBSCRIPT_WRITE", // §2-7c
            "PLUS_EQUALS_REBIND",               // §2-8
            "DUPLICATE",                        // §2-9
            "SIGNAL_ARG",                       // §2-10
            "FOR_ITER",                         // §2-12
            "APPEND_ARRAY_ALIAS",               // §2-13
            "RESIZE_ALIAS",                     // §2-14
            "INDEX_WRITE_ALIAS",                // §2-15
            "VARIANT_IDENTITY",                 // §2-16
            "PARAM_DEFAULT_SHARED",             // §2-17
            "ELEMENT_REBIND",                   // §2-18
            "STRING_ITER_ELEMENTS",             // §2-19
            "IN_MEMBERSHIP",                    // §2-20
            "EQUALITY",                         // §2-21
            "DICT_KEY_HASH",                    // §2-21 (hash)
            "AS_SAME_FAMILY",                   // §2-22
            "CORO_AWAIT",                       // §2-23
            "SIGNAL_MULTI",                     // §2-24
            "DYNAMIC_VARIANT_MUTATION",         // §5 dynamic Variant receiver route
            "STATIC_VAR",                       // §2-4
            "LAMBDA_CAPTURE",                   // §2-11
            "MIXED_COMBINATION",                // Phase F mixed scenario
            "CONTROL_FLOW_BRANCHES",            // Phase F: complex if/elif/match branch mutation
            "RETURN_VALUE_SHARING",             // Phase F: packed return-value identity forms
            "ENGINE_METHOD_PACKED_ARG",         // Phase F: packed arg/return at engine methods
            "STRING_ARRAY_MUTATION"             // Phase F: PackedStringArray mutation/equality
    );

    /// The golden must contain exactly the locked §2 matrix inventory, in matrix order — no
    /// row silently dropped, no unregistered case added. Payload truth still lives only in
    /// the golden file; this anchors coverage.
    @Test
    void goldenCoversExactlyTheLockedMatrixInventory() throws IOException {
        assertEquals(MATRIX_CASE_NAMES, PackedRefSemanticsDualRunHarness.loadGolden().caseNames());
    }
}
